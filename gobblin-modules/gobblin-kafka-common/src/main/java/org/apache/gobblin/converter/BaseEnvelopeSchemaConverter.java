/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.converter;

import java.nio.ByteBuffer;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.gobblin.configuration.WorkUnitState;
import org.apache.gobblin.metrics.kafka.KafkaSchemaRegistry;
import org.apache.gobblin.metrics.kafka.KafkaSchemaRegistryFactory;
import org.apache.gobblin.util.AvroUtils;

import com.google.common.base.Optional;


/**
 * Base class for an envelope schema converter using {@link KafkaSchemaRegistry}
 */
public abstract class BaseEnvelopeSchemaConverter<P> extends Converter<Schema, Schema, GenericRecord, GenericRecord> {
  public static final String PAYLOAD_SCHEMA_ID_FIELD = "converter.envelopeSchemaConverter.schemaIdField";
  public static final String PAYLOAD_FIELD = "converter.envelopeSchemaConverter.payloadField";
  public static final String PAYLOAD_SCHEMA_TOPIC = "converter.envelopeSchemaConverter.payloadSchemaTopic";
  public static final String KAFKA_REGISTRY_FACTORY = "converter.envelopeSchemaConverter.kafkaRegistryFactory";

  public static final String DEFAULT_PAYLOAD_FIELD = "payload";
  public static final String DEFAULT_PAYLOAD_SCHEMA_ID_FIELD = "payloadSchemaId";
  public static final String DEFAULT_KAFKA_SCHEMA_REGISTRY_FACTORY_CLASS =
      "org.apache.gobblin.metrics.kafka.KafkaAvroSchemaRegistryFactory";

  protected String payloadSchemaIdField;
  protected String payloadField;
  protected String payloadSchemaTopic;
  protected GenericDatumReader<P> latestPayloadReader;
  protected KafkaSchemaRegistry registry;

  @Override
  public BaseEnvelopeSchemaConverter init(WorkUnitState workUnit) {
    super.init(workUnit);

    payloadSchemaIdField = workUnit.getProp(PAYLOAD_SCHEMA_ID_FIELD, DEFAULT_PAYLOAD_SCHEMA_ID_FIELD);
    payloadField = workUnit.getProp(PAYLOAD_FIELD, DEFAULT_PAYLOAD_FIELD);

    // Get the schema specific topic to fetch the schema in the registry
    if (!workUnit.contains(PAYLOAD_SCHEMA_TOPIC)) {
      throw new RuntimeException("Configuration not found: " + PAYLOAD_SCHEMA_TOPIC);
    }
    payloadSchemaTopic = workUnit.getProp(PAYLOAD_SCHEMA_TOPIC);

    String registryFactoryField = workUnit.getProp(KAFKA_REGISTRY_FACTORY, DEFAULT_KAFKA_SCHEMA_REGISTRY_FACTORY_CLASS);
    try {
      KafkaSchemaRegistryFactory registryFactory =
          ((Class<? extends KafkaSchemaRegistryFactory>) Class.forName(registryFactoryField)).newInstance();
      registry = registryFactory.create(workUnit.getProperties());
    } catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
      throw new RuntimeException(e);
    }
    return this;
  }

  /**
   * Get the payload schema
   *
   * @param inputRecord the input record which has the payload
   * @return the current schema of the payload
   */
  protected Schema getPayloadSchema(GenericRecord inputRecord)
      throws Exception {
    Optional<Object> schemaIdValue = AvroUtils.getFieldValue(inputRecord, payloadSchemaIdField);
    if (!schemaIdValue.isPresent()) {
      throw new Exception("Schema id with key " + payloadSchemaIdField + " not found in the record");
    }
    String schemaKey = String.valueOf(schemaIdValue.get());
    return (Schema) registry.getSchemaByKey(schemaKey);
  }

  /**
   * Get payload field and convert to byte array
   *
   * @param inputRecord the input record which has the payload
   * @return the byte array of the payload in the input record
   */
  protected byte[] getPayloadBytes(GenericRecord inputRecord) {
    ByteBuffer bb = (ByteBuffer) inputRecord.get(payloadField);
    if (bb.hasArray()) {
      return bb.array();
    } else {
      byte[] payloadBytes = new byte[bb.remaining()];
      bb.get(payloadBytes);
      return payloadBytes;
    }
  }

  protected Schema fetchLatestPayloadSchema() throws Exception {
    Schema latestPayloadSchema = (Schema)registry.getLatestSchemaByTopic(payloadSchemaTopic);
    latestPayloadReader = new GenericDatumReader<>(latestPayloadSchema);
    return latestPayloadSchema;
  }

  /**
   * Convert the payload in the input record to a deserialized object with the latest schema
   *
   * @param inputRecord the input record
   * @return the schema'ed payload object
   */
  protected P upConvertPayload(GenericRecord inputRecord) throws DataConversionException {
    try {
      Schema payloadSchema = getPayloadSchema(inputRecord);
      // Set writer schema
      latestPayloadReader.setSchema(payloadSchema);

      byte[] payloadBytes = getPayloadBytes(inputRecord);
      Decoder decoder = DecoderFactory.get().binaryDecoder(payloadBytes, null);

      // 'latestPayloadReader.read' will convert the record from 'payloadSchema' to the latest payload schema
      return latestPayloadReader.read(null, decoder);
    } catch (Exception e) {
      throw new DataConversionException(e);
    }
  }
}
