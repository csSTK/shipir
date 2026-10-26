package dev.bethge.shipir.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Shared output sanitizer: C1 controls, bidi embedding/override and isolate characters become spaces. */
class OutputSanitizerTest {

    @Test
    void neutralizesC1ControlsAndBidiFormatCharactersInBuildLogLine() {
        String line = "Step 1/3 : RUN echo \u0085x\u009Fy‮z⁦w⁩";

        assertThat(OutputSanitizer.neutralize(line)).isEqualTo("Step 1/3 : RUN echo  x y z w ");
    }

    @Test
    void neutralizesEveryC1ControlAndEveryBidiCharacterInTheRanges() {
        StringBuilder controls = new StringBuilder();
        for (char c = 0x0080; c <= 0x009F; c++) {
            controls.append(c);
        }
        for (char c = 0x202A; c <= 0x202E; c++) {
            controls.append(c);
        }
        for (char c = 0x2066; c <= 0x2069; c++) {
            controls.append(c);
        }

        assertThat(OutputSanitizer.neutralize(controls.toString())).isEqualTo(" ".repeat(controls.length()));
    }

    @Test
    void keepsTabsPrintableTextAndNonAsciiLetters() {
        String line = "Name\tvalue: Grüße 漢字 ‮";

        assertThat(OutputSanitizer.neutralize("Name\tvalue: Grüße 漢字 ")).isEqualTo("Name\tvalue: Grüße 漢字 ");
        assertThat(OutputSanitizer.neutralize(line)).isEqualTo("Name\tvalue: Grüße 漢字  ");
    }

    @Test
    void neutralizesAsciiControlCharacters() {
        assertThat(OutputSanitizer.neutralize("a\u0007b\u001Bc\u007Fd")).isEqualTo("a b c d");
    }
}
