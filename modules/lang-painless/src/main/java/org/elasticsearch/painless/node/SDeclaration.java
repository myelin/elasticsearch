/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.Definition;
import org.elasticsearch.painless.Definition.Type;
import org.elasticsearch.painless.Variables;
import org.elasticsearch.painless.Variables.Variable;
import org.objectweb.asm.Opcodes;
import org.elasticsearch.painless.MethodWriter;

/**
 * Represents a single variable declaration.
 */
public final class SDeclaration extends AStatement {

    final String type;
    final String name;
    AExpression expression;

    Variable variable;

    public SDeclaration(int line, int offset, String location, String type, String name, AExpression expression) {
        super(line, offset, location);

        this.type = type;
        this.name = name;
        this.expression = expression;
    }

    @Override
    void analyze(Variables variables) {
        final Type type;

        try {
            type = Definition.getType(this.type);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(error("Not a type [" + this.type + "]."));
        }

        if (expression != null) {
            expression.expected = type;
            expression.analyze(variables);
            expression = expression.cast(variables);
        }

        variable = variables.addVariable(location, type, name, false, false);
    }

    @Override
    void write(MethodWriter writer) {
        writeDebugInfo(writer);

        if (expression == null) {
            switch (variable.type.sort) {
                case VOID:   throw new IllegalStateException(error("Illegal tree structure."));
                case BOOL:
                case BYTE:
                case SHORT:
                case CHAR:
                case INT:    writer.push(0);    break;
                case LONG:   writer.push(0L);   break;
                case FLOAT:  writer.push(0.0F); break;
                case DOUBLE: writer.push(0.0);  break;
                default:     writer.visitInsn(Opcodes.ACONST_NULL);
            }
        } else {
            expression.write(writer);
        }

        writer.visitVarInsn(variable.type.type.getOpcode(Opcodes.ISTORE), variable.slot);
    }
}
