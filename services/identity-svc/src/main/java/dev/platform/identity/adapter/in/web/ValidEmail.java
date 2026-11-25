package dev.platform.identity.adapter.in.web;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The string must be accepted by the domain {@code Email} value object. */
@Documented
@Constraint(validatedBy = ValidEmailValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@interface ValidEmail {

  String message() default "must be a valid email address";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
