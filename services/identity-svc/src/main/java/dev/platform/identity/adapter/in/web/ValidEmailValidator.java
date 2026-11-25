package dev.platform.identity.adapter.in.web;

import dev.platform.identity.domain.Email;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

class ValidEmailValidator implements ConstraintValidator<ValidEmail, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    try {
      new Email(value);
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }
}
