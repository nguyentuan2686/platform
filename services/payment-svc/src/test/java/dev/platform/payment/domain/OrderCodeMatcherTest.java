package dev.platform.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderCodeMatcherTest {

  @Test
  void lowercase_code_is_matched() {
    assertThat(OrderCodeMatcher.candidates("tv8k2qab chuyen tien")).containsExactly("TV8K2QAB");
  }

  @Test
  void code_glued_to_bank_text_is_matched() {
    assertThat(OrderCodeMatcher.candidates("MBVCB.123.TV8K2QABFT25330")).contains("TV8K2QAB");
  }

  @Test
  void two_codes_give_two_candidates() {
    assertThat(OrderCodeMatcher.candidates("TV8K2QAB TV9M3RCD")).hasSize(2);
  }

  @Test
  void overlapping_codes_are_both_found() {
    assertThat(OrderCodeMatcher.candidates("TV2TV3456AB")).contains("TV3456AB");
  }

  @Test
  void no_code_gives_nothing() {
    assertThat(OrderCodeMatcher.candidates("chuyen tien")).isEmpty();
    assertThat(OrderCodeMatcher.candidates(null)).isEmpty();
    assertThat(OrderCodeMatcher.candidates("")).isEmpty();
  }

  @Test
  void ambiguous_letters_are_not_a_code() {
    assertThat(OrderCodeMatcher.candidates("TV0O1IL2")).isEmpty();
  }

  @Test
  void both_site_prefixes_are_recognised() {
    assertThat(OrderCodeMatcher.candidates("CT234567")).containsExactly("CT234567");
  }
}
