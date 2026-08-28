package pl.fanth.gitsync.prompt;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PushUpdatePromptTest {

    /** A restart mid-session reads the answers back off disk, so nothing in them may be lost. */
    @Test
    void readsASavedSessionBack() {
        String json = """
            {
              "starter": "Steve",
              "message": "a commit message",
              "confirmed": true,
              "plugins": [
                {"jar":"Thing-1.0.jar","name":"Thing","configPaths":["Thing"],"reloadCommands":["thing reload"],"wildcard":"Thing-*.jar","submitted":true,"ignored":false}
              ],
              "pluginGroups": [
                {"owner":"Thing","drafts":[{"path":"Thing-1.0.jar","layer":"base"},{"path":"Thing/config.yml","layer":"server/lobby"}]}
              ],
              "fileGroups": [
                {"owner":"Other files","drafts":[{"path":"other.yml","layer":"role/lobby"}]}
              ],
              "tracked": [
                {"logicalPath":"Thing/messages.yml","kind":"MODIFIED","targetLayer":"base"}
              ],
              "index": 2,
              "page": 1,
              "generation": 7
            }""";

        Gson gson = new Gson();
        PushUpdatePrompt prompt = gson.fromJson(json, PushUpdatePrompt.class);

        assertEquals(JsonParser.parseString(json), JsonParser.parseString(gson.toJson(prompt)));
    }
}
