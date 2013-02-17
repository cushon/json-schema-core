/*
 * Copyright (c) 2013, Francis Galiegue <fgaliegue@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Lesser GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Lesser GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.fge.jsonschema.tree;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.fge.jsonschema.exceptions.JsonReferenceException;
import com.github.fge.jsonschema.jsonpointer.JsonPointer;
import com.github.fge.jsonschema.jsonpointer.TokenResolver;
import com.github.fge.jsonschema.ref.JsonRef;
import com.github.fge.jsonschema.util.JacksonUtils;

/**
 * A {@link JsonTree} carrying URI resolution context information
 *
 * <p>In addition to what {@link JsonTree} does, this tree also modifies URI
 * resolution context information when changing paths, and adds methods in order
 * to query this resolution context.</p>
 *
 * <p>All context information is carried as JSON References, since this is what
 * is used for addressing in JSON Schema.</p>
 *
 * @see com.github.fge.jsonschema.ref.JsonRef
 * @see CanonicalSchemaTree
 * @see InlineSchemaTree
 */
public abstract class BaseSchemaTree
    implements SchemaTree
{
    private static final JsonNodeFactory FACTORY = JacksonUtils.nodeFactory();

    /**
     * The contents of {@code $schema} for that schema
     *
     * <p>Note that it is required that if it is present, it be an absolute
     * JSON Reference. If no suitable {@code $schema} is found, an empty ref
     * is returned.</p>
     */
    private final JsonRef dollarSchema;

    /**
     * The initial node
     */
    protected final JsonNode baseNode;

    /**
     * The current JSON Pointer into the node. Starts empty.
     */
    protected final JsonPointer pointer;

    /**
     * The current node.
     */
    private final JsonNode node;

    /**
     * The JSON Reference from which this node has been loaded
     *
     * <p>If loaded without a URI, this will be the empty reference.</p>
     */
    protected final JsonRef loadingRef;

    /**
     * The JSON Reference representing the context at the root of the schema
     *
     * <p>It will defer from {@link #loadingRef} if there is an {@code id} at
     * the top level.</p>
     */
    private final JsonRef startingRef;

    /**
     * The current resolution context
     */
    private final JsonRef currentRef;

    protected BaseSchemaTree(final JsonRef loadingRef, final JsonNode baseNode,
        final JsonPointer pointer)
    {
        dollarSchema = extractDollarSchema(baseNode);
        this.baseNode = baseNode;
        this.pointer = pointer;
        node = pointer.path(baseNode);
        this.loadingRef = loadingRef;


        final JsonRef ref = idFromNode(baseNode);

        startingRef = ref == null ? loadingRef : loadingRef.resolve(ref);

        currentRef = nextRef(startingRef, pointer, baseNode);
    }

    protected BaseSchemaTree(final BaseSchemaTree other,
        final JsonPointer newPointer)
    {
        dollarSchema = other.dollarSchema;
        baseNode = other.baseNode;
        loadingRef = other.loadingRef;

        pointer = newPointer;
        node = newPointer.get(baseNode);

        startingRef = other.startingRef;
        currentRef = nextRef(startingRef, newPointer, baseNode);
    }

    @Override
    public final JsonNode getBaseNode()
    {
        return baseNode;
    }

    @Override
    public final JsonPointer getPointer()
    {
        return pointer;
    }

    @Override
    public final JsonNode getNode()
    {
        return node;
    }

    /**
     * Resolve a JSON Reference against the current resolution context
     *
     * @param other the JSON Reference to resolve
     * @return the resolved reference
     * @see com.github.fge.jsonschema.ref.JsonRef#resolve(com.github.fge.jsonschema.ref.JsonRef)
     */
    @Override
    public final JsonRef resolve(final JsonRef other)
    {
        return currentRef.resolve(other);
    }

    @Override
    public final JsonRef getDollarSchema()
    {
        return dollarSchema;
    }

    /**
     * Get the loading URI for that schema
     *
     * @return the loading URI as a {@link com.github.fge.jsonschema.ref.JsonRef}
     */
    @Override
    public final JsonRef getLoadingRef()
    {
        return loadingRef;
    }

    /**
     * Get the current resolution context
     *
     * @return the context as a {@link com.github.fge.jsonschema.ref.JsonRef}
     */
    @Override
    public final JsonRef getContext()
    {
        return currentRef;
    }

    @Override
    public final JsonNode asJson()
    {
        final ObjectNode ret = FACTORY.objectNode();

        ret.put("loadingURI", FACTORY.textNode(loadingRef.toString()));
        ret.put("pointer", FACTORY.textNode(pointer.toString()));

        return ret;
    }

    @Override
    public final String toString()
    {
        return "loading URI: " + loadingRef
            + "; current pointer: " + pointer
            + "; resolution context: " + currentRef;
    }

    /**
     * Build a JSON Reference from a node
     *
     * <p>This will return {@code null} if the reference could not be built. The
     * conditions for a successful build are as follows:</p>
     *
     * <ul>
     *     <li>the node is an object;</li>
     *     <li>it has a member named {@code id};</li>
     *     <li>the value of this member is a string;</li>
     *     <li>this string is a valid URI.</li>
     * </ul>
     *
     * @param node the node
     * @return a JSON Reference, or {@code null}
     */
    protected static JsonRef idFromNode(final JsonNode node)
    {
        if (!node.path("id").isTextual())
            return null;

        try {
            return JsonRef.fromString(node.get("id").textValue());
        } catch (JsonReferenceException ignored) {
            return null;
        }
    }

    /**
     * Calculate the next URI context from a starting reference and node
     *
     * @param startingRef the starting reference
     * @param ptr the JSON Pointer
     * @param startingNode the starting node
     * @return the calculated reference
     */
    private static JsonRef nextRef(final JsonRef startingRef,
        final JsonPointer ptr, final JsonNode startingNode)
    {
        JsonRef ret = startingRef;
        JsonRef idRef;
        JsonNode node = startingNode;

        for (final TokenResolver<JsonNode> resolver: ptr) {
            node = resolver.get(node);
            if (node == null)
                break;
            idRef = idFromNode(node);
            if (idRef != null)
                ret = ret.resolve(idRef);
        }

        return ret;
    }

    private static JsonRef extractDollarSchema(final JsonNode schema)
    {
        final JsonNode node = schema.path("$schema");

        if (!node.isTextual())
            return JsonRef.emptyRef();

        try {
            final JsonRef ref = JsonRef.fromString(node.textValue());
            return ref.isAbsolute() ? ref : JsonRef.emptyRef();
        } catch (JsonReferenceException ignored) {
            return JsonRef.emptyRef();
        }
    }
}
