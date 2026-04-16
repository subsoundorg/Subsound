package org.subsound.persistence;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.Test;

import static org.subsound.persistence.SongCache.toCacheKey;
import static org.assertj.core.api.Assertions.assertThat;

public class DBSongCacheTest {

    @Test
    public void getSong() {
    }

    @Test
    public void toCacheKeyTest() {
        var shasum = DigestUtils.sha256Hex("1");
        var key = toCacheKey("1");
        assertThat(key.part1()).isEqualTo("6b");
        assertThat(key.part2()).isEqualTo("86");
        assertThat(key.part3()).isEqualTo("b2");
        assertThat(String.join("", key.part1(), key.part2(), key.part3())).isEqualTo(shasum.substring(0, 6));
    }

    @Test
    public void joinPath() {
    }
}