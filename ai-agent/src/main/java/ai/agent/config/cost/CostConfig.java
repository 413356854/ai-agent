package ai.agent.config.cost;

import ai.agent.service.cost.CostPricing;
import ai.agent.config.CostProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 成本看板的装配（阶段四）。
 *
 * <h2>为什么 {@link CostPricing} 用 {@code @Bean} 而不是 {@code @Component}</h2>
 * 因为价格表是**构造参数**，而它可能在测试里被换成一张假的表。
 * 标 {@code @Component} 的话，测试要覆盖价格就只能去改全局配置
 * （{@code @TestPropertySource} 或 {@code @DynamicPropertySource}）——
 * 那会让"测计价算术"这件事需要启动整个容器。
 *
 * <p>用 {@code @Bean} + {@code @ConditionalOnMissingBean} 之后，
 * 单元测试可以直接 {@code new CostPricing(假的价格表)}，一个容器都不用起。
 *
 * <p>⚠️ 这个类里的 {@code @Bean} 方法**不是**那个"一个类两处注册"的坑
 * （见「一个类只能由一个地方注册」）：{@link CostPricing} 自己没有 {@code @Component}，
 * 所以容器里只会有这一个定义。「一个类只能由一个地方注册」的反例是"同一个类既有 {@code @Component}
 * 又有 {@code @Bean}"，两者并存时条件装配会安静地跳过其中一个。
 */
@Configuration
@EnableConfigurationProperties(CostProperties.class)
public class CostConfig {

	/**
	 * 计价器。
	 *
	 * <p>{@code @ConditionalOnMissingBean} 让测试可以注入一张自定义的价格表 ——
	 * 而"测试里能不能换掉价格"这件事很重要：真实的单价会变，
	 * 把断言建立在具体数字上，会让测试在调价之后莫名其妙地红
	 * （阶段一踩过同族的坑：测试依赖了估算系数的具体取值）。
	 */
	@Bean
	@org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(CostPricing.class)
	CostPricing costPricing(CostProperties properties) {
		return new CostPricing(properties);
	}

}
