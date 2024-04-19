/*
 * Copyright 2020 Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.registry.rules.compatibility;

import com.google.common.collect.ImmutableSet;
import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.rules.UnprocessableSchemaException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.avro.AvroRuntimeException;
import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.Incompatibility;
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityResult;

/**
 * @author Ales Justin
 * @author Jonathan Halliday
 * @author Jakub Senko <em>m@jsenko.net</em>
 */
public class AvroCompatibilityChecker extends AbstractCompatibilityChecker<Incompatibility> {
  @Override
  protected Set<Incompatibility> isBackwardsCompatibleWith(
      String existing, String proposed, Map<String, ContentHandle> resolvedReferences) {
    try {
      Schema.Parser existingParser = new Schema.Parser();
      for (ContentHandle schema : resolvedReferences.values()) {
        existingParser.parse(schema.content());
      }
      final Schema existingSchema = existingParser.parse(existing);

      Schema.Parser proposingParser = new Schema.Parser();
      for (ContentHandle schema : resolvedReferences.values()) {
        proposingParser.parse(schema.content());
      }
      final Schema proposedSchema = proposingParser.parse(proposed);

      var result =
          SchemaCompatibility.checkReaderWriterCompatibility(proposedSchema, existingSchema)
              .getResult();
      switch (result.getCompatibility()) {
        case COMPATIBLE:
          return Collections.emptySet();
        case INCOMPATIBLE:
          {
            return ImmutableSet.<Incompatibility>builder()
                .addAll(result.getIncompatibilities())
                .build();
          }
        default:
          throw new IllegalStateException(
              "Got illegal compatibility result: " + result.getCompatibility());
      }
    } catch (AvroRuntimeException ex) {
      throw new UnprocessableSchemaException(
          "Could not execute compatibility rule on invalid Avro schema", ex);
    }
  }

  protected Set<Incompatibility> isParquetCompatibleWith(
      String existing, String proposed, Map<String, ContentHandle> resolvedReferences) {
    var stdIncompat = isBackwardsCompatibleWith(existing, proposed, resolvedReferences);

    try {
      Schema.Parser existingParser = new Schema.Parser();
      for (ContentHandle schema : resolvedReferences.values()) {
        existingParser.parse(schema.content());
      }
      final Schema existingSchema = existingParser.parse(existing);

      Schema.Parser proposingParser = new Schema.Parser();
      for (ContentHandle schema : resolvedReferences.values()) {
        proposingParser.parse(schema.content());
      }
      final Schema proposedSchema = proposingParser.parse(proposed);
      Deque<String> location = new ArrayDeque<>();
      location.addLast("");
      return ImmutableSet.<Incompatibility>builder()
          .addAll(stdIncompat)
          .addAll(findParquetIncompat(existingSchema, proposedSchema, location))
          .build();

    } catch (AvroRuntimeException ex) {
      throw new UnprocessableSchemaException(
          "Could not execute compatibility rule on invalid Avro schema", ex);
    }
  }

  private HashSet<Incompatibility> findParquetIncompat(
      Schema existingSchema, Schema proposedSchema, Deque<String> location) {
    var incompat = new HashSet<Incompatibility>();
    location.addLast(existingSchema.getName());
    if (existingSchema.getType() != proposedSchema.getType()) {
      incompat.addAll(
          SchemaCompatibilityResult.incompatible(
                  SchemaCompatibility.SchemaIncompatibilityType.TYPE_MISMATCH,
                  existingSchema,
                  proposedSchema,
                  "Type mismatch",
                  new ArrayList<>(location))
              .getIncompatibilities());
    }
    switch (existingSchema.getType()) {
      case RECORD:
        for (Schema.Field srcField : existingSchema.getFields()) {
          location.addLast(srcField.name());
          Schema.Field dstField =
              proposedSchema.getFields().stream()
                  .filter(x -> Objects.equals(x.name(), srcField.name()))
                  .findFirst()
                  .orElse(null);
          if (dstField == null) {
            incompat.addAll(
                SchemaCompatibilityResult.incompatible(
                        SchemaCompatibility.SchemaIncompatibilityType.TYPE_MISMATCH,
                        existingSchema,
                        proposedSchema,
                        "Cannot find field " + srcField.name(),
                        new ArrayList<>(location))
                    .getIncompatibilities());
            location.removeLast();
            continue;
          }
          incompat.addAll(findParquetIncompat(srcField.schema(), dstField.schema(), location));
          location.removeLast();
        }
        break;
      case MAP:
        incompat.addAll(
            findParquetIncompat(
                existingSchema.getValueType(), proposedSchema.getValueType(), location));
        break;
      case ARRAY:
        incompat.addAll(
            findParquetIncompat(
                existingSchema.getElementType(), proposedSchema.getElementType(), location));
        break;
      case ENUM:
        var enums = new HashSet<>(existingSchema.getEnumSymbols());
        enums.removeAll(proposedSchema.getEnumSymbols());
        if (!enums.isEmpty()) {
          incompat.addAll(
              SchemaCompatibilityResult.incompatible(
                      SchemaCompatibility.SchemaIncompatibilityType.TYPE_MISMATCH,
                      existingSchema,
                      proposedSchema,
                      "Missing enum values: " + enums,
                      new ArrayList<>(location))
                  .getIncompatibilities());
        }
        break;
      case UNION:
        if (existingSchema.getTypes().size() != proposedSchema.getTypes().size()) {
          incompat.addAll(
              SchemaCompatibilityResult.incompatible(
                      SchemaCompatibility.SchemaIncompatibilityType.TYPE_MISMATCH,
                      existingSchema,
                      proposedSchema,
                      "Cannot change number of types in union",
                      new ArrayList<>(location))
                  .getIncompatibilities());
        }
        break;
    }
    location.removeLast();
    return incompat;
  }

  @Override
  protected CompatibilityDifference transform(Incompatibility original) {
    return new SimpleCompatibilityDifference(original.getMessage(), original.getLocation());
  }
}
