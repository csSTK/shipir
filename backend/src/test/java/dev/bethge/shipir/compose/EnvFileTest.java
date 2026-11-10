package dev.bethge.shipir.compose;

import dev.bethge.shipir.compose.dto.EnvEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** shipr-kqbc.64: parse also understands the forms of a hand-written or Git-supplied .env. */
class EnvFileTest {

    private static List<String> pairs(String text) {
        return EnvFile.parse(text).stream().map(e -> e.key() + "=" + e.value()).toList();
    }

    @Test
    void parsesExportPrefix() {
        assertThat(pairs("export KEY=value\nexport   OTHER='a b'\n")).containsExactly("KEY=value", "OTHER=a b");
    }

    @Test
    void stripsInlineCommentFromUnquotedValue() {
        assertThat(pairs("KEY=value # a comment\nB=x\t# tab comment\n")).containsExactly("KEY=value", "B=x");
    }

    @Test
    void keepsHashInsideQuotesAndWithoutLeadingSpace() {
        assertThat(pairs("A='x # y'\nB=\"p # q\"\nC=a#b\nD='x' # trailing\n"))
                .containsExactly("A=x # y", "B=p # q", "C=a#b", "D=x");
    }

    @Test
    void handlesCrlfLineEndings() {
        assertThat(pairs("KEY=value\r\nB='q'\r\n# c\r\nC=\r\n")).containsExactly("KEY=value", "B=q", "C=");
    }

    @Test
    void trimsSpacesAroundKey() {
        assertThat(pairs("  KEY =value\n")).containsExactly("KEY=value");
    }

    @Test
    void roundTripsRenderedValues() {
        List<EnvEntry> in = List.of(new EnvEntry("A", "plain"), new EnvEntry("B", "with space # hash"),
                new EnvEntry("C", "it's $x"), new EnvEntry("D", ""));
        assertThat(EnvFile.parse(EnvFile.render(in))).containsExactlyElementsOf(in);
    }
}
