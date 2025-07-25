/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.dataflow.worker;

import static org.apache.beam.runners.dataflow.util.Structs.getString;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkState;

import com.google.auto.service.AutoService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.apache.beam.runners.dataflow.util.CloudObject;
import org.apache.beam.runners.dataflow.worker.util.common.worker.Sink;
import org.apache.beam.runners.dataflow.worker.windmill.Windmill;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.KvCoder;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.transforms.windowing.PaneInfo.PaneInfoCoder;
import org.apache.beam.sdk.util.ByteStringOutputStream;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.ValueWithRecordId;
import org.apache.beam.sdk.values.ValueWithRecordId.ValueWithRecordIdCoder;
import org.apache.beam.sdk.values.WindowedValue;
import org.apache.beam.sdk.values.WindowedValues.FullWindowedValueCoder;
import org.apache.beam.vendor.grpc.v1p69p0.com.google.protobuf.ByteString;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableMap;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
class WindmillSink<T> extends Sink<WindowedValue<T>> {
  private WindmillStreamWriter writer;
  private final Coder<T> valueCoder;
  private final Coder<Collection<? extends BoundedWindow>> windowsCoder;
  private StreamingModeExecutionContext context;
  private static final Logger LOG = LoggerFactory.getLogger(WindmillSink.class);

  WindmillSink(
      String destinationName,
      Coder<WindowedValue<T>> coder,
      StreamingModeExecutionContext context) {
    this.writer = new WindmillStreamWriter(destinationName);
    FullWindowedValueCoder<T> inputCoder = (FullWindowedValueCoder<T>) coder;
    this.valueCoder = inputCoder.getValueCoder();
    this.windowsCoder = inputCoder.getWindowsCoder();
    this.context = context;
  }

  public static ByteString encodeMetadata(
      Coder<Collection<? extends BoundedWindow>> windowsCoder,
      Collection<? extends BoundedWindow> windows,
      PaneInfo paneInfo)
      throws IOException {
    ByteStringOutputStream stream = new ByteStringOutputStream();
    PaneInfoCoder.INSTANCE.encode(paneInfo, stream);
    windowsCoder.encode(windows, stream, Coder.Context.OUTER);
    return stream.toByteString();
  }

  public static PaneInfo decodeMetadataPane(ByteString metadata) throws IOException {
    InputStream inStream = metadata.newInput();
    return PaneInfoCoder.INSTANCE.decode(inStream);
  }

  public static Collection<? extends BoundedWindow> decodeMetadataWindows(
      Coder<Collection<? extends BoundedWindow>> windowsCoder, ByteString metadata)
      throws IOException {
    InputStream inStream = metadata.newInput();
    PaneInfoCoder.INSTANCE.decode(inStream);
    return windowsCoder.decode(inStream, Coder.Context.OUTER);
  }

  /** A {@link SinkFactory.Registrar} for windmill sinks. */
  @AutoService(SinkFactory.Registrar.class)
  public static class Registrar implements SinkFactory.Registrar {

    @Override
    public Map<String, SinkFactory> factories() {
      Factory factory = new Factory();
      return ImmutableMap.of(
          "WindmillSink", factory,
          "org.apache.beam.runners.dataflow.worker.WindmillSink", factory);
    }
  }

  public static class Factory implements SinkFactory {
    @Override
    public WindmillSink<?> create(
        CloudObject spec,
        Coder<?> coder,
        @Nullable PipelineOptions options,
        @Nullable DataflowExecutionContext executionContext,
        DataflowOperationContext operationContext)
        throws Exception {

      @SuppressWarnings("unchecked")
      Coder<WindowedValue<Object>> typedCoder = (Coder<WindowedValue<Object>>) coder;
      return new WindmillSink<>(
          getString(spec, "stream_id"),
          typedCoder,
          (StreamingModeExecutionContext) executionContext);
    }
  }

  @Override
  public SinkWriter<WindowedValue<T>> writer() {
    return writer;
  }

  class WindmillStreamWriter implements SinkWriter<WindowedValue<T>> {
    private Map<ByteString, Windmill.KeyedMessageBundle.Builder> productionMap;
    private final String destinationName;
    private final ByteStringOutputStream stream; // Kept across encodes for buffer reuse.

    private WindmillStreamWriter(String destinationName) {
      this.destinationName = destinationName;
      productionMap = new HashMap<>();
      stream = new ByteStringOutputStream();
    }

    private <EncodeT> ByteString encode(Coder<EncodeT> coder, EncodeT object) throws IOException {
      checkState(
          stream.size() == 0,
          "Expected output stream to be empty but had %s",
          stream.toByteString());
      coder.encode(object, stream, Coder.Context.OUTER);
      return stream.toByteStringAndReset();
    }

    @Override
    @SuppressWarnings("NestedInstanceOfConditions")
    public long add(WindowedValue<T> data) throws IOException {
      ByteString key, value;
      ByteString id = ByteString.EMPTY;
      ByteString metadata = encodeMetadata(windowsCoder, data.getWindows(), data.getPaneInfo());
      if (valueCoder instanceof KvCoder) {
        KvCoder kvCoder = (KvCoder) valueCoder;
        KV kv = (KV) data.getValue();
        key = encode(kvCoder.getKeyCoder(), kv.getKey());
        Coder valueCoder = kvCoder.getValueCoder();
        // If ids are explicitly provided, use that instead of the windmill-generated id.
        // This is used when reading an UnboundedSource to deduplicate records.
        if (valueCoder instanceof ValueWithRecordId.ValueWithRecordIdCoder) {
          ValueWithRecordId valueAndId = (ValueWithRecordId) kv.getValue();
          value =
              encode(((ValueWithRecordIdCoder) valueCoder).getValueCoder(), valueAndId.getValue());
          id = ByteString.copyFrom(valueAndId.getId());
        } else {
          value = encode(valueCoder, kv.getValue());
        }
      } else {
        key = context.getSerializedKey();
        value = encode(valueCoder, data.getValue());
      }
      if (key.size() > context.getMaxOutputKeyBytes()) {
        if (context.throwExceptionsForLargeOutput()) {
          throw new OutputTooLargeException("Key too large: " + key.size());
        } else {
          LOG.error(
              "Trying to output too large key with size "
                  + key.size()
                  + ". Limit is "
                  + context.getMaxOutputKeyBytes()
                  + ". See https://cloud.google.com/dataflow/docs/guides/common-errors#key-commit-too-large-exception."
                  + " Running with --experiments=throw_exceptions_on_large_output will instead throw an OutputTooLargeException which may be caught in user code.");
        }
      }
      if (value.size() > context.getMaxOutputValueBytes()) {
        if (context.throwExceptionsForLargeOutput()) {
          throw new OutputTooLargeException("Value too large: " + value.size());
        } else {
          LOG.error(
              "Trying to output too large value with size "
                  + value.size()
                  + ". Limit is "
                  + context.getMaxOutputValueBytes()
                  + ". See https://cloud.google.com/dataflow/docs/guides/common-errors#key-commit-too-large-exception."
                  + " Running with --experiments=throw_exceptions_on_large_output will instead throw an OutputTooLargeException which may be caught in user code.");
        }
      }

      Windmill.KeyedMessageBundle.Builder keyedOutput = productionMap.get(key);
      if (keyedOutput == null) {
        keyedOutput = Windmill.KeyedMessageBundle.newBuilder().setKey(key);
        productionMap.put(key, keyedOutput);
      }

      Windmill.Message.Builder builder =
          Windmill.Message.newBuilder()
              .setTimestamp(WindmillTimeUtils.harnessToWindmillTimestamp(data.getTimestamp()))
              .setData(value)
              .setMetadata(metadata);
      keyedOutput.addMessages(builder.build());

      long offsetSize = 0;
      if (context.offsetBasedDeduplicationSupported()) {
        if (id.size() > 0) {
          throw new RuntimeException(
              "Unexpected record ID via ValueWithRecordIdCoder while offset-based deduplication enabled.");
        }
        byte[] rawId = context.getCurrentRecordId();
        if (rawId.length == 0) {
          throw new RuntimeException(
              "Unexpected empty record ID while offset-based deduplication enabled.");
        }
        id = ByteString.copyFrom(rawId);

        byte[] rawOffset = context.getCurrentRecordOffset();
        if (rawOffset.length == 0) {
          throw new RuntimeException(
              "Unexpected empty record offset while offset-based deduplication enabled.");
        }
        ByteString offset = ByteString.copyFrom(rawOffset);
        offsetSize = offset.size();
        keyedOutput.addMessageOffsets(offset);
      }

      keyedOutput.addMessagesIds(id);

      return (long) key.size() + value.size() + metadata.size() + id.size() + offsetSize;
    }

    @Override
    public void close() throws IOException {
      Windmill.OutputMessageBundle.Builder outputBuilder =
          Windmill.OutputMessageBundle.newBuilder().setDestinationStreamId(destinationName);

      for (Windmill.KeyedMessageBundle.Builder keyedOutput : productionMap.values()) {
        outputBuilder.addBundles(keyedOutput.build());
      }
      if (outputBuilder.getBundlesCount() > 0) {
        context.getOutputBuilder().addOutputMessages(outputBuilder.build());
      }
      productionMap.clear();
    }

    @Override
    public void abort() throws IOException {
      close();
    }
  }

  @Override
  public boolean supportsRestart() {
    return true;
  }
}
