package com.devteam.seckill.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置（Stage 5 缓存三件套 / Stage 6 分布式锁用）。
 * key 用 String 序列化，value 用 JSON 序列化，便于 redis-cli 直接观察。
 *
 * <h2>为什么必须手工配 ObjectMapper</h2>
 * <p>项目里的实体（{@code Product}）带 {@code LocalDateTime createdAt}。Jackson 默认
 * <b>不认识</b> Java 8 时间类型，直接序列化会抛
 * {@code InvalidDefinitionException: Java 8 date/time type LocalDateTime not supported by default}。
 * 这个异常发生在"写缓存"的那一刻，而"写缓存失败"通常被当成缓存不生效——现场表现就是
 * "缓存怎么都不命中、每次都打 DB"，很难联想到是序列化。所以这里显式注册
 * {@link JavaTimeModule}。</p>
 *
 * <p>另外要开启 <b>类型信息</b>（{@code activateDefaultTyping}）：RedisTemplate 的 value 类型是
 * {@code Object}，反序列化时如果 JSON 里不带 {@code @class}，拿回来只能是
 * {@code LinkedHashMap} 而不是 {@code Product}——于是"缓存命中"之后取出来的对象根本用不了。
 * 这是"缓存看起来生效了、但一读就类型转换异常"的经典坑。</p>
 *
 * <p>说明：RedisTemplate 的 connectionFactory 是懒连接，Stage 0/1 即使 Redis 没起也不影响启动与冒烟测试。</p>
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer(mapper);

        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }
}
