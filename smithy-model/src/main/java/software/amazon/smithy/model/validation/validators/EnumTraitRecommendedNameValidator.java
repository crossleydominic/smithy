/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package software.amazon.smithy.model.validation.validators;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import software.amazon.smithy.model.Model;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.traits.EnumDefinition;
import software.amazon.smithy.model.traits.EnumTrait;
import software.amazon.smithy.model.validation.AbstractValidator;
import software.amazon.smithy.model.validation.ValidationEvent;

/**
 * Ensures that enum trait names adhere to the recommended pattern.
 *
 * <p>Enum names should adhere to the RECOMMENDED_NAME_PATTERN.
 */
public final class EnumTraitRecommendedNameValidator extends AbstractValidator {
    private static final Pattern RECOMMENDED_NAME_PATTERN = Pattern.compile("^[A-Z]+[A-Z_0-9]*$");

    @Override
    public List<ValidationEvent> validate(Model model) {
        List<ValidationEvent> events = new ArrayList<>();

        for (Shape shape : model.getShapesWithTrait(EnumTrait.class)) {
            events.addAll(validateEnumTrait(shape, shape.expectTrait(EnumTrait.class)));
        }

        return events;
    }

    private List<ValidationEvent> validateEnumTrait(Shape shape, EnumTrait trait) {
        List<ValidationEvent> events = new ArrayList<>();

        // Ensure that names are unique.
        for (EnumDefinition definition : trait.getValues()) {
            if (definition.getName().isPresent()) {
                String name = definition.getName().get();
                if (!RECOMMENDED_NAME_PATTERN.matcher(name).find()) {
                    events.add(warning(shape, trait, String.format(
                            "The name `%s` does not match the recommended enum name format of beginning with an "
                            + "uppercase letter, followed by any number of uppercase letters, numbers, or underscores.",
                            name)));
                }
            }
        }

        return events;
    }
}
