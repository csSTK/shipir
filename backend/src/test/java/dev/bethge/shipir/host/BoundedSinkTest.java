package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Image build/push output passed to the stream: C1 controls and bidi/format characters never reach the frames. */
class BoundedSinkTest {

    @Test
    void stripsC1ControlsAndBidiCharactersFromBuildLogLine() {
        List<String> forwarded = new ArrayList<>();
        ImageBuildService.BoundedSink sink = new ImageBuildService.BoundedSink(forwarded::add);

        sink.accept("Step 2/4 : COPY \u0080a\u009Fb‭c⁧d⁩e");

        assertThat(forwarded).containsExactly("Step 2/4 : COPY  a b c d e");
    }
}
