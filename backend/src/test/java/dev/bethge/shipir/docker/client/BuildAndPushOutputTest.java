package dev.bethge.shipir.docker.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.api.model.PushResponseItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Build log and push progress lines reach the output without C1 controls or bidi/format characters. */
class BuildAndPushOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void buildLogLineStripsC1ControlsAndBidiCharacters() throws Exception {
        List<String> lines = new ArrayList<>();
        ImageServiceImpl.BuildOutput output = new ImageServiceImpl.BuildOutput(lines::add);

        output.onNext(item("{\"stream\":\"Step 1/3 : RUN echo \\u0080x\\u009Fy\\u202Ez\\u2066w\\u2069\\n\"}"));

        assertThat(lines).containsExactly("Step 1/3 : RUN echo  x y z w");
    }

    @Test
    void buildStatusLineStripsC1ControlsAndBidiCharacters() throws Exception {
        List<String> lines = new ArrayList<>();
        ImageServiceImpl.BuildOutput output = new ImageServiceImpl.BuildOutput(lines::add);

        output.onNext(item("{\"id\":\"layer\\u202E1\",\"status\":\"Pulling\\u0085 fs layer\"}"));

        assertThat(lines).containsExactly("layer 1: Pulling  fs layer");
    }

    @Test
    void pushProgressLineStripsC1ControlsAndBidiCharacters() throws Exception {
        List<String> lines = new ArrayList<>();
        ImageServiceImpl.PushProgress progress = new ImageServiceImpl.PushProgress(lines::add);

        progress.onNext(pushItem("{\"id\":\"abc\",\"status\":\"Pushed\\u2069\\u009F\"}"));

        assertThat(lines).containsExactly("abc: Pushed  ");
    }

    private static BuildResponseItem item(String json) throws Exception {
        return JSON.readValue(json, BuildResponseItem.class);
    }

    private static PushResponseItem pushItem(String json) throws Exception {
        return JSON.readValue(json, PushResponseItem.class);
    }
}
