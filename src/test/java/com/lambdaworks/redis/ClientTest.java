/*
 * Copyright 2011-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lambdaworks.redis;

import static com.google.code.tempusfugit.temporal.Duration.seconds;
import static com.google.code.tempusfugit.temporal.WaitFor.waitOrTimeout;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import com.lambdaworks.TestClientResources;
import org.junit.FixMethodOrder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runners.MethodSorters;

import com.google.code.tempusfugit.temporal.Condition;
import com.google.code.tempusfugit.temporal.Timeout;
import com.lambdaworks.redis.api.StatefulRedisConnection;
import com.lambdaworks.redis.api.async.RedisAsyncCommands;

/**
 * @author Will Glozer
 * @author Mark Paluch
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class ClientTest extends AbstractRedisClientTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();

    @Override
    public void openConnection() throws Exception {
        super.openConnection();
    }

    @Override
    public void closeConnection() throws Exception {
        super.closeConnection();
    }

    @Test(expected = RedisException.class)
    public void close() throws Exception {
        redis.getStatefulConnection().close();
        redis.get(key);
    }

    @Test
    public void statefulConnectionFromSync() throws Exception {
        assertThat(redis.getStatefulConnection().sync()).isSameAs(redis);
    }

    @Test
    public void statefulConnectionFromAsync() throws Exception {
        RedisAsyncCommands<String, String> async = client.connect().async();
        assertThat(async.getStatefulConnection().async()).isSameAs(async);
        async.getStatefulConnection().close();
    }

    @Test
    public void statefulConnectionFromReactive() throws Exception {
        RedisAsyncCommands<String, String> async = client.connect().async();
        assertThat(async.getStatefulConnection().reactive().getStatefulConnection()).isSameAs(async.getStatefulConnection());
        async.getStatefulConnection().close();
    }

    @Test
    public void listenerTest() throws Exception {

        final TestConnectionListener listener = new TestConnectionListener();

        RedisClient client = RedisClient.create(RedisURI.Builder.redis(host, port).build());

        client.addListener(listener);

        assertThat(listener.onConnected).isNull();
        assertThat(listener.onDisconnected).isNull();
        assertThat(listener.onException).isNull();

        RedisAsyncCommands<String, String> connection = client.connect().async();

        StatefulRedisConnection<String, String> statefulRedisConnection = connection.getStatefulConnection();

        waitOrTimeout(() -> listener.onConnected != null, Timeout.timeout(seconds(2)));

        assertThat(listener.onConnected).isEqualTo(statefulRedisConnection);
        assertThat(listener.onDisconnected).isNull();

        connection.set(key, value).get();
        connection.getStatefulConnection().close();

        waitOrTimeout(new Condition() {

            @Override
            public boolean isSatisfied() {
                return listener.onDisconnected != null;
            }
        }, Timeout.timeout(seconds(2)));

        assertThat(listener.onConnected).isEqualTo(statefulRedisConnection);
        assertThat(listener.onDisconnected).isEqualTo(statefulRedisConnection);

        FastShutdown.shutdown(client);
    }

    @Test
    public void listenerTestWithRemoval() throws Exception {

        final TestConnectionListener removedListener = new TestConnectionListener();
        final TestConnectionListener retainedListener = new TestConnectionListener();

        RedisClient client = RedisClient.create(RedisURI.Builder.redis(host, port).build());
        client.addListener(removedListener);
        client.addListener(retainedListener);
        client.removeListener(removedListener);

        // that's the sut call
        client.connect().async();

        waitOrTimeout(() -> retainedListener.onConnected != null, Timeout.timeout(seconds(2)));

        assertThat(retainedListener.onConnected).isNotNull();

        assertThat(removedListener.onConnected).isNull();
        assertThat(removedListener.onDisconnected).isNull();
        assertThat(removedListener.onException).isNull();

        FastShutdown.shutdown(client);

    }

    @Test(expected = RedisException.class)
    public void timeout() throws Exception {
        redis.setTimeout(0, TimeUnit.MICROSECONDS);
        redis.eval(" os.execute(\"sleep \" .. tonumber(1))", ScriptOutputType.STATUS);
    }

    @Test
    public void reconnect() throws Exception {

        redis.set(key, value);

        redis.quit();
        Thread.sleep(100);
        assertThat(redis.get(key)).isEqualTo(value);
        redis.quit();
        Thread.sleep(100);
        assertThat(redis.get(key)).isEqualTo(value);
        redis.quit();
        Thread.sleep(100);
        assertThat(redis.get(key)).isEqualTo(value);
    }

    @Test(expected = RedisCommandInterruptedException.class, timeout = 50)
    public void interrupt() throws Exception {
        Thread.currentThread().interrupt();
        redis.blpop(0, key);
    }

    @Test
    public void connectFailure() throws Exception {
        RedisClient client = RedisClient.create(TestClientResources.get(), "redis://invalid");
        exception.expect(RedisException.class);
        exception.expectMessage("Unable to connect");
        client.connect();
        FastShutdown.shutdown(client);
    }

    @Test
    public void connectPubSubFailure() throws Exception {
        RedisClient client = RedisClient.create(TestClientResources.get(), "redis://invalid");
        exception.expect(RedisException.class);
        exception.expectMessage("Unable to connect");
        client.connectPubSub();
        FastShutdown.shutdown(client);
    }

    private class TestConnectionListener implements RedisConnectionStateListener {

        public RedisChannelHandler<?, ?> onConnected;
        public RedisChannelHandler<?, ?> onDisconnected;
        public RedisChannelHandler<?, ?> onException;

        @Override
        public void onRedisConnected(RedisChannelHandler<?, ?> connection) {
            onConnected = connection;
        }

        @Override
        public void onRedisDisconnected(RedisChannelHandler<?, ?> connection) {
            onDisconnected = connection;
        }

        @Override
        public void onRedisExceptionCaught(RedisChannelHandler<?, ?> connection, Throwable cause) {
            onException = connection;

        }
    }

    @Test
    public void emptyClient() throws Exception {

        RedisClient client = DefaultRedisClient.get();
        try {
            client.connect();
        } catch (IllegalStateException e) {
            assertThat(e).hasMessageContaining("RedisURI");
        }

        try {
            client.connect().async();
        } catch (IllegalStateException e) {
            assertThat(e).hasMessageContaining("RedisURI");
        }

        try {
            client.connect((RedisURI) null);
        } catch (IllegalArgumentException e) {
            assertThat(e).hasMessageContaining("RedisURI");
        }
    }

    @Test
    public void testExceptionWithCause() throws Exception {
        RedisException e = new RedisException(new RuntimeException());
        assertThat(e).hasCauseExactlyInstanceOf(RuntimeException.class);
    }

    @Test(timeout = 20000)
    public void reset() throws Exception {
        StatefulRedisConnection<String, String> connection = client.connect();
        RedisAsyncCommands<String, String> async = connection.async();

        connection.sync().set(key, value);
        async.reset();
        connection.sync().set(key, value);
        connection.sync().flushall();

        RedisFuture<KeyValue<String, String>> eval = async.blpop(2, key);
        Thread.sleep(100);
        assertThat(eval.isDone()).isFalse();
        assertThat(eval.isCancelled()).isFalse();

        async.reset();

        assertThat(eval.isCancelled()).isTrue();
        assertThat(eval.isDone()).isTrue();

        connection.close();
    }

    @Test
    public void standaloneConnectionShouldSetClientName() throws Exception {

        RedisURI redisURI = RedisURI.create(host, port);
        redisURI.setClientName("my-client");

        StatefulRedisConnection<String, String> connection = client.connect(redisURI);

        assertThat(connection.sync().clientGetname()).isEqualTo(redisURI.getClientName());

        connection.sync().quit();
        assertThat(connection.sync().clientGetname()).isEqualTo(redisURI.getClientName());

        connection.close();
    }

    @Test
    public void pubSubConnectionShouldSetClientName() throws Exception {

        RedisURI redisURI = RedisURI.create(host, port);
        redisURI.setClientName("my-client");

        StatefulRedisConnection<String, String> connection = client.connectPubSub(redisURI);

        assertThat(connection.sync().clientGetname()).isEqualTo(redisURI.getClientName());

        connection.sync().quit();
        assertThat(connection.sync().clientGetname()).isEqualTo(redisURI.getClientName());

        connection.close();
    }

}
