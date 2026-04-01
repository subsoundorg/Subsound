package org.subsound.configuration;

import org.subsound.integration.ServerClient.ServerType;
import org.junit.Test;

import static org.subsound.configuration.Config.parseCfg;
import static org.assertj.core.api.Assertions.assertThat;

public class ConfigTest {

    @Test
    public void testLegacyConfigFormat() {
        assertThat(parseCfg(LEGACY_CONFIG_JSON)).isPresent().hasValueSatisfying(dto -> {
            assertThat(dto.server).isNotNull();
            assertThat(dto.server.url()).isEqualTo("https://play.example.org");
            assertThat(dto.server.username()).isEqualTo("username");
            assertThat(dto.server.password()).isEqualTo("password");
            assertThat(dto.server.type()).isEqualTo(ServerType.SUBSONIC);
            assertThat(dto.serverId).isNull();
        });
    }

    @Test
    public void testNewConfigFormat() {
        assertThat(parseCfg(NEW_CONFIG_JSON)).isPresent().hasValueSatisfying(dto -> {
            assertThat(dto.serverId).isEqualTo("abc-123");
            assertThat(dto.server).isNull();
            assertThat(dto.windowWidth).isEqualTo(1400);
            assertThat(dto.windowHeight).isEqualTo(900);
        });
    }

    private static final String LEGACY_CONFIG_JSON = """
            {
              "server": {
                "type": "SUBSONIC",
                "url": "https://play.example.org",
                "username": "username",
                "password": "password"
              }
            }""";

    private static final String NEW_CONFIG_JSON = """
            {
              "serverId": "abc-123",
              "windowWidth": 1400,
              "windowHeight": 900
            }""";
}