package pl.fanth.gitsync.config;

import eu.okaeri.configs.OkaeriConfig;
import eu.okaeri.configs.annotation.Comment;
import eu.okaeri.configs.annotation.Header;

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
}
