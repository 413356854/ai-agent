package ai.agent.config;

import ai.agent.mapper.handler.InstantTypeHandler;
import ai.agent.mapper.handler.LocalDateTypeHandler;
import ai.agent.mapper.handler.PromptKindTypeHandler;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis 的装配：<b>自定义类型转换器</b>。
 *
 * <h2>为什么三个转换器要显式注册，而不是只靠 {@code @MappedTypes}</h2>
 * {@code @MappedTypes} 只在"按包/按类扫描注册"时才被读取；一个只被 {@code @Bean}
 * 返回、或被 XML 用 {@code typeHandler="..."} 引用的 handler，注解并不会让它自动生效。
 * 而"XML 引用了但没注册"的失败形态很隐蔽：MyBatis 会退回默认转换器，
 * 于是时间字段被按驱动的默认时区解释（值可能差几小时）、
 * {@code prompt kind} 直接抛 {@code No enum constant}。这两件事都不会在启动时报出来。
 * 所以这里显式注册，让 XML 里写不写 {@code typeHandler} 都得到同一套规则。
 *
 * <p>三者的具体理由各自写在类注释里：
 * {@link InstantTypeHandler}（可空 + 时区语义）、
 * {@link LocalDateTypeHandler}（那一列的类型跨库不保证）、
 * {@link PromptKindTypeHandler}（库里存的是 defaultValue，不是枚举名）。
 *
 * <h2>mapper 接口是怎么被扫到的</h2>
 * 由主类上的 {@code @MapperScan("ai.agent.mapper")} 负责 ——
 * {@code ai.agent.mapper} 是"SQL 与 Java 的边界"那一个包，它的语义是"这里全是 mapper"，
 * 把这个事实写在主类上（而不是让它隐式成立），读者一眼就能找到那个包。
 */
@Configuration
public class MyBatisConfig {

	@Bean
	public ConfigurationCustomizer mybatisTypeHandlers() {
		return configuration -> {
			configuration.getTypeHandlerRegistry().register(InstantTypeHandler.class);
			configuration.getTypeHandlerRegistry().register(LocalDateTypeHandler.class);
			configuration.getTypeHandlerRegistry().register(PromptKindTypeHandler.class);
		};
	}

}
