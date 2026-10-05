package com.arman.bank.userservice
import com.arman.bank.userservice.infrastructure.ProfileShards
import spock.lang.Specification
class ProfileShardConfigSpec extends Specification {
    def 'invalid configurations reject before opening any database pool'() {
        when:
        // No DB_URL is supplied: each malformed configuration must fail before credentials are read.
        ProfileShards.open(config)
        then:
        def failure = thrown(IllegalArgumentException)
        !failure.message.startsWith('Missing environment variable')
        where:
        config << [
            [USER_SHARD_IDS: 'east,east'],
            [USER_SHARD_IDS: 'primary'],
            [USER_SHARD_IDS: 'East'],
            [USER_SHARD_IDS: 'east,'],
            [USER_SHARD_IDS: 'east', USER_SHARD_PREFIX_MAP: 'ab:west'],
            [USER_SHARD_IDS: 'east', USER_SHARD_PREFIX_MAP: 'ab:east,ab:primary'],
            [USER_SHARD_IDS: 'east', USER_SHARD_PREFIX_MAP: 'a:east'],
            [USER_SHARD_IDS: 'east', USER_SHARD_PREFIX_MAP: 'ab:east:primary'],
            [USER_SHARD_IDS: 'east', USER_SHARD_DEFAULT: 'west']
        ]
    }

}

