/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.linters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.loader.Prelude;
import software.amazon.smithy.model.node.ArrayNode;
import software.amazon.smithy.model.node.Node;
import software.amazon.smithy.model.node.ObjectNode;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.Trait;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * <p>Base class for any validator that wants to perform a full text search on a Model.
 *
 * <p>Does a full scan of the model and gathers all text along with location data
 * and uses a generic function to decide what to do with each text occurrence. The
 * full text search traversal has no knowledge of what it is looking for and descends
 * fully into the structure of all traits.
 *
 * <p>Prelude shape definitions are not examined, however all values
 */
public abstract class AbstractModelTextValidator extends AbstractValidator {
    private static final Map<Model, List<TextOccurrence>> MODEL_TO_TEXT_MAP = new ConcurrentHashMap<>();

    /**
     * Sub-classes must implement this method to take the following responsibilities:
     *
     * 1) Decide if the text occurrence is
     * 2)
     *
     * @param occurrence
     * @param validationEventConsumer
     */
    protected abstract void getValidationEvents(TextOccurrence occurrence,
                                                Consumer<ValidationEvent> validationEventConsumer);

    @Override
    public List<ValidationEvent> validate(Model model) {
        final Set<String> visited = new HashSet<>();
        List<TextOccurrence> textOccurrences = MODEL_TO_TEXT_MAP.computeIfAbsent(model, keyModel -> {
            List<TextOccurrence> texts = new LinkedList<>();
            model.shapes().filter(shape -> !Prelude.isPreludeShape(shape)).forEach(shape -> {
                getTextOccurrences(shape, texts, visited);
            });
            return texts;
        });

        List<ValidationEvent> validationEvents = new LinkedList<>();
        for (TextOccurrence text:textOccurrences) {
            getValidationEvents(text, validationEvent -> {
                validationEvents.add(validationEvent);
            });
        }

        return validationEvents;
    }

    private static void getTextOccurrences(Shape shape, Collection<TextOccurrence> textOccurences,
                                           Set<String> shapeIdsVisited) {
        if (!shapeIdsVisited.contains(shape.getId().toString())) {
            shapeIdsVisited.add(shape.getId().toString());
            if (!shape.isMemberShape()) {
                textOccurences.add(TextOccurrence.builder()
                        .shape(shape)
                        .text(shape.getId().getName())
                        .build());
            } else {
                textOccurences.add(TextOccurrence.builder()
                        .shape(shape)
                        .text(shape.getId().getMember().get())
                        .build());
            }

            //iterate over the text contained in trait property values
            shape.getAllTraits().entrySet().forEach(traitEntry -> {
                getTextElementsForTrait(traitEntry.getValue().toNode(), traitEntry.getValue(), shape, textOccurences,
                        new Stack<>());
            });
            //then iterate over all of it's members
            shape.members().forEach(memberShape -> {
                getTextOccurrences(memberShape, textOccurences, shapeIdsVisited);
            });
        }
    }

    private static void getTextElementsForTrait(Node node, Trait trait,
                                         Shape parentShape,
                                         Collection<TextOccurrence> textOccurrences,
                                         Stack<String> propertyPath) {
        if (node.isStringNode()) {
            textOccurrences.add(TextOccurrence.builder()
                    .shape(parentShape)
                    .trait(trait)
                    .traitPropertyPath(propertyPath)
                    .text(node.expectStringNode().getValue())
                    .build());
        } else if (node.isObjectNode()) {
            ObjectNode objectNode = node.expectObjectNode();
            objectNode.getMembers().entrySet().forEach(memberEntry -> {
                String pathPrefix = propertyPath.isEmpty()
                        ? ""
                        : ".";
                propertyPath.push(pathPrefix + memberEntry.getKey().getValue());
                //Test the key. TODO: If possible, we do not need to test Trait modeled keys
                //but within "document" type values in a trait
                textOccurrences.add(TextOccurrence.builder()
                        .shape(parentShape)
                        .trait(trait)
                        .text(memberEntry.getKey().getValue())
                        .traitPropertyPath(propertyPath)
                        .isTraitKeyName(true)
                        .build());
                if (memberEntry.getValue().isStringNode()) {
                    textOccurrences.add(TextOccurrence.builder()
                            .shape(parentShape)
                            .trait(trait)
                            .text(memberEntry.getValue().asStringNode().get().getValue())
                            .traitPropertyPath(propertyPath)
                            .build());
                } else {
                    getTextElementsForTrait(memberEntry.getValue(), trait, parentShape, textOccurrences, propertyPath);
                }
                propertyPath.pop();
            });
        } else if (node.isArrayNode()) {
            ArrayNode arrayNode = node.expectArrayNode();
            final int[] index = {0};
            arrayNode.getElements().forEach(nodeElement -> {
                propertyPath.push("[" + index[0] + "]");
                getTextElementsForTrait(nodeElement, trait, parentShape, textOccurrences, propertyPath);
                propertyPath.pop();
                ++index[0];
            });
        }
        //by now it's not a string value to look at, or any further structure to descend so there's nothing to do
    }

    static final class TextOccurrence {
        final String text;                       //the actual text value, or name, with the problem text
        final Shape shape;                       //If the problem is with the Shape itself, it's always the name
        final Optional<Trait> trait;             //If present, the problem text is in the applied trait
        final List<String> traitPropertyPath;    //Gives the property path in the trait value with the problem text
        final boolean isTraitKeyName;            //If set to true, the problem text is the last key in the
                                                 // property path, not the value

        private TextOccurrence(String text, Shape shape, Optional<Trait> trait, List<String> propertyPath,
                               boolean isTraitKeyName) {
            if (propertyPath == null) {
                throw new RuntimeException();
            }
            this.text = text;
            this.shape = shape;
            this.trait = trait;
            this.traitPropertyPath = propertyPath;
            this.isTraitKeyName = isTraitKeyName;
        }

        public static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private String text;
            private Shape shape;
            private Trait trait;
            private List<String> traitPropertyPath = new ArrayList<>();
            private boolean isTraitKeyName;

            private Builder() { }

            public Builder shape(Shape shape) {
                this.shape = shape;
                return this;
            }

            public Builder trait(Trait trait) {
                this.trait = trait;
                return this;
            }

            public Builder traitPropertyPath(List<String> traitPropertyPath) {
                this.traitPropertyPath = traitPropertyPath != null
                    ? new LinkedList(traitPropertyPath)
                    : new LinkedList<>();
                return this;
            }

            public Builder isTraitKeyName(boolean isTraitKeyName) {
                this.isTraitKeyName = isTraitKeyName;
                return this;
            }

            public Builder text(String text) {
                this.text = text;
                return this;
            }

            public TextOccurrence build() {
                if (shape == null) {
                    throw new IllegalStateException("Shape must be specified");
                }
                if (text == null) {
                    throw new IllegalStateException("Text must be specified");
                }

                Optional<Trait> traitArg = trait != null
                        ? Optional.of(trait)
                        : Optional.empty();
                return new TextOccurrence(text, shape, traitArg, traitPropertyPath, isTraitKeyName);
            }
        }
    }
}
