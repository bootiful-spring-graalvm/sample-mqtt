package com.example.hivemq;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.internal.SSLNetworkModuleFactory;
import org.eclipse.paho.client.mqttv3.internal.TCPNetworkModuleFactory;
import org.eclipse.paho.client.mqttv3.internal.websocket.WebSocketNetworkModuleFactory;
import org.eclipse.paho.client.mqttv3.internal.websocket.WebSocketSecureNetworkModuleFactory;
import org.eclipse.paho.client.mqttv3.logging.JSR47Logger;
import org.eclipse.paho.client.mqttv3.logging.Logger;
import org.eclipse.paho.client.mqttv3.logging.LoggerFactory;
import org.eclipse.paho.client.mqttv3.spi.NetworkModuleFactory;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.MessageChannels;
import org.springframework.integration.handler.GenericHandler;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.outbound.MqttPahoMessageHandler;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.Map;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@ImportRuntimeHints(HivemqApplication.MqttHints.class)
@SpringBootApplication
public class HivemqApplication {

    public static void main(String[] args) {
        SpringApplication.run(HivemqApplication.class, args);
    }

    @Bean
    MqttPahoClientFactory mqttPahoClientFactory() {
        var factory = new DefaultMqttPahoClientFactory();
        var options = new MqttConnectOptions();
        options.setServerURIs(new String[]{"tcp://localhost:1884"});
        factory.setConnectionOptions(options);
        return factory;
    }

    static class MqttHints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, ClassLoader classLoader) {

            var classes = new Class<?>[]{
                    JSR47Logger.class,
                    LoggerFactory.class,
                    NetworkModuleFactory.class,
                    WebSocketNetworkModuleFactory.class,
                    WebSocketSecureNetworkModuleFactory.class,
                    SSLNetworkModuleFactory.class,
                    TCPNetworkModuleFactory.class,
                    Logger.class};

            var resources = Map.of(
                    "org/eclipse/paho/client/mqttv3/logging/JSR47Logger.class", false, //
                    "org/eclipse/paho/client/mqttv3/internal/nls/logcat.properties", false, //
                    "org/eclipse/paho/client/mqttv3/internal/nls/messages", true
            );

            for (var c : classes) {
                hints.reflection().registerType(c, MemberCategory.values());
            }

            for (var entry : resources.entrySet()) {
                var bundle = entry.getValue();
                var path = entry.getKey();
                if (bundle)
                    hints.resources().registerResourceBundle(path);
                else
                    hints.resources().registerPattern(path);

            }
        }
    }
}

@Configuration
class OutConfiguration {

    @Bean
    MessageChannel out() {
        return MessageChannels.direct().get();
    }

    @Bean
    MqttPahoMessageHandler outboundAdapter(
            @Value("${hivemq.topic}") String topic,
            MqttPahoClientFactory factory) {
        var mh = new MqttPahoMessageHandler("producer", factory);
        mh.setDefaultTopic(topic);
        return mh;
    }

    @Bean
    IntegrationFlow outboundFlow(MessageChannel out, MqttPahoMessageHandler outboundAdapter) {
        return IntegrationFlow
                .from(out)
                .handle(outboundAdapter)
                .get();
    }

    @Bean
    RouterFunction<ServerResponse> routes(MessageChannel out) {
        return route()
                .GET("/send/{name}", request -> {
                    var name = request.pathVariable("name");
                    var msg = MessageBuilder.withPayload(name).build();
                    out.send(msg);
                    return ServerResponse.ok().build();
                })
                .build();
    }
}

@Configuration
class InConfiguration {

    @Bean
    IntegrationFlow inboundFlow(MqttPahoMessageDrivenChannelAdapter inboundAdapter) {
        return IntegrationFlow
                .from(inboundAdapter)
                .handle((GenericHandler<String>) (payload, headers) -> {
                    System.out.println("new message: " + payload);
                    headers.forEach((k, v) -> System.out.println(k + "=" + v));
                    return null;
                })
                .get();
    }

    @Bean
    MqttPahoMessageDrivenChannelAdapter inboundAdapter(
            MqttPahoClientFactory clientFactory, @Value("${hivemq.topic}") String topic) {
        return new MqttPahoMessageDrivenChannelAdapter("consumer", clientFactory, topic);
    }
}