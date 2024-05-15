package io.apicurio.registry.rules;

import org.apache.avro.Schema;
import org.apache.avro.SchemaCompatibility;
import org.apache.avro.SchemaCompatibility.Incompatibility;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

public class AvroCustomChecker {

  public static Set<Incompatibility> validate(Schema schema) {
    var l = new ArrayDeque<String>();
    l.addLast("");
    return checkLogicalTypes(schema, l);
  }

  private static Set<Incompatibility> checkLogicalTypes(Schema schema, Deque<String> location) {
    var issues = new HashSet<Incompatibility>();

    switch (schema.getType()) {
      case RECORD:
        for (Schema.Field fld : schema.getFields()) {
          location.addLast(fld.name());
          if (fld.getProp("logicalType") != null) {
            issues.addAll(
                SchemaCompatibility.SchemaCompatibilityResult.incompatible(
                        SchemaCompatibility.SchemaIncompatibilityType.TYPE_MISMATCH,
                        schema,
                        schema,
                        "invalid logical type",
                        new ArrayList<>(location))
                    .getIncompatibilities());
          }
          issues.addAll(checkLogicalTypes(fld.schema(), location));
          location.removeLast();
        }
        break;
      case ARRAY:
        issues.addAll(checkLogicalTypes(schema.getElementType(), location));
        break;
      case MAP:
        issues.addAll(checkLogicalTypes(schema.getValueType(), location));
        break;
      case UNION:
        for (Schema s : schema.getTypes()) {
          location.addLast(s.getName());
          issues.addAll(checkLogicalTypes(s, location));
          location.removeLast();
        }
    }

    return issues;
  }
}
