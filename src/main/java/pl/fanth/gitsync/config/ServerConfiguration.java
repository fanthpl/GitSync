package pl.fanth.gitsync.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;

import java.util.LinkedHashMap;
import java.util.Map;

@Header("Identity of this server inside the network. It decides which layers of the pack")
@Header("are rendered on top of base/, and is never synced.")
@Header("")
public class ServerConfiguration extends OkaeriConfig {
    @Comment("Server type, rendered from role/<role>/ over base/. Shared by every server of this kind.")
    @Comment("Supports environment placeholders, e.g. \"${GITSYNC_ROLE}\".")
    public String role = "";

    @Comment("This one server, rendered from instance/<instance>/ over the role. Empty for none.")
    @Comment("Supports environment placeholders, e.g. \"${GITSYNC_INSTANCE}\".")
    public String instance = "";

    @Comment("")
    @Comment("What this server puts into the synced files. A ${NAME} written in a packed file")
    @Comment("becomes the value given here when the file is rendered, and goes back to ${NAME}")
    @Comment("when /gitsync pushupdate publishes it - so a database password or a server")
    @Comment("name stays on this server while the file itself stays shared.")
    @Comment("Values support environment placeholders too. Example:")
    @Comment("  variables:")
    @Comment("    SERVER_NAME: \"lobby-1\"")
    @Comment("    DB_PASSWORD: \"${DB_PASSWORD}\"")
    public Map<String, String> variables = new LinkedHashMap<>();
}
