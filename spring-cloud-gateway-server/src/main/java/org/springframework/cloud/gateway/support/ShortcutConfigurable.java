/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.gateway.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.cloud.gateway.handler.predicate.CookieRoutePredicateFactory;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.Expression;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.Assert;

/**
 *
 *
 * <p>
 * spring  cloud gateway 快捷配置类。
 *
 * <p>
 *    <ol>支持predicate</ol>
 *    <ol>支持 filter</ol>
 *    <ol>组织规则：[PredicateName|FilterName]=[key],[value],例如 {@link CookieRoutePredicateFactory}
 *    可以配置为 Cookie=NyCookie，MyCookieValue
 *    </ol>
 * </p>
 *
 */

public interface ShortcutConfigurable {

	/**
	 * 一般情况返回当前传入key
	 */
	static String normalizeKey(String key, int entryIdx, ShortcutConfigurable argHints, Map<String, String> args) {
		// RoutePredicateFactory has name hints and this has a fake key name
		// replace with the matching key hint
		if (key.startsWith(NameUtils.GENERATED_NAME_PREFIX) && !argHints.shortcutFieldOrder().isEmpty()
				&& entryIdx < args.size() && entryIdx < argHints.shortcutFieldOrder().size()) {
			key = argHints.shortcutFieldOrder().get(entryIdx);
		}
		return key;
	}

	/**
	 * 支持spel 表达式
	 */
	static Object getValue(SpelExpressionParser parser, BeanFactory beanFactory, String entryValue) {
		Object value;
		// 默认值
		String rawValue = entryValue;
		if (rawValue != null) {
			rawValue = rawValue.trim();
		}

		// 解析spel 值
		if (rawValue != null && rawValue.startsWith("#{") && entryValue.endsWith("}")) {
			// assume it's spel
			StandardEvaluationContext context = new StandardEvaluationContext();
			context.setBeanResolver(new BeanFactoryResolver(beanFactory));
			Expression expression = parser.parseExpression(entryValue, new TemplateParserContext());
			value = expression.getValue(context);
		}
		else {
			value = entryValue;
		}
		return value;
	}

	default ShortcutType shortcutType() {
		return ShortcutType.DEFAULT;
	}

	/**
	 *
	 *
	 *
	 * Returns hints about the number of args and the order for shortcut parsing.
	 * @return the list of hints
	 */
	default List<String> shortcutFieldOrder() {
		return Collections.emptyList();
	}

	default String shortcutFieldPrefix() {
		return "";
	}

	enum ShortcutType {

		/**
		 *
		 * 主要解析 args 键值对
		 */
		DEFAULT {
			@Override
			public Map<String, Object> normalize(Map<String, String> args, ShortcutConfigurable shortcutConf,
					SpelExpressionParser parser, BeanFactory beanFactory) {
				Map<String, Object> map = new HashMap<>();
				int entryIdx = 0;
				for (Map.Entry<String, String> entry : args.entrySet()) {
					String key = normalizeKey(entry.getKey(), entryIdx, shortcutConf, args);
					Object value = getValue(parser, beanFactory, entry.getValue());

					map.put(key, value);
					entryIdx++;
				}
				return map;
			}
		},

		/**
		 *
		 * <p>shortcutConf 一般只含有一个 字段</p>
		 * <p> 返回的map 只有一个元素,并且以 shortcutConf 的唯一字段做键， 以 args 的 值对象解析并合并成 list 作为返回map的值 </p>
		 *
		 */
		GATHER_LIST {
			@Override
			public Map<String, Object> normalize(Map<String, String> args, ShortcutConfigurable shortcutConf,
					SpelExpressionParser parser, BeanFactory beanFactory) {
				Map<String, Object> map = new HashMap<>();
				// field order should be of size 1
				List<String> fieldOrder = shortcutConf.shortcutFieldOrder();
				Assert.isTrue(fieldOrder != null && fieldOrder.size() == 1,
						"Shortcut Configuration Type GATHER_LIST must have shortcutFieldOrder of size 1");
				String fieldName = fieldOrder.get(0);
				map.put(fieldName, args.values().stream().map(value -> getValue(parser, beanFactory, value))
						.collect(Collectors.toList()));
				return map;
			}
		},

		/**
		 *
		 * <p>
		 *     <ul>
		 *         <li>和 {@link #GATHER_LIST} 相似，只不过 args 对应的最后一个元素 是 bool 类型；</li>
		 *         <li>返回两个元素</li>
		 *         <li>shortcutConf 只能包含两个元素</li>
		 *         <li>shortcutConf 最后一个元素做键，对应的字段 为 args values 最后一个值</li>
		 *         <li>shortcutConf 第一个元素做键，对应的字段 为 args values移除最后一个值形成的集合list</li>
		 *     </ul>
		 * </p>
		 *
		 */
		// list is all elements except last which is a boolean flag
		GATHER_LIST_TAIL_FLAG {
			@Override
			public Map<String, Object> normalize(Map<String, String> args, ShortcutConfigurable shortcutConf,
					SpelExpressionParser parser, BeanFactory beanFactory) {
				Map<String, Object> map = new HashMap<>();
				// field order should be of size 1
				List<String> fieldOrder = shortcutConf.shortcutFieldOrder(); // shortcutConf 字段集合
				Assert.isTrue(fieldOrder != null && fieldOrder.size() == 2,
						"Shortcut Configuration Type GATHER_LIST_HEAD must have shortcutFieldOrder of size 2"); // shortcutConf 字段集合必须只能包含两个元素
				List<String> values = new ArrayList<>(args.values()); // 将 args 的 values 取出
				if (!values.isEmpty()) {
					// strip boolean flag if last entry is true or false
					int lastIdx = values.size() - 1;
					String lastValue = values.get(lastIdx); // 取values 的最后一个元素
					if (lastValue.equalsIgnoreCase("true") || lastValue.equalsIgnoreCase("false")) { // 判断最后一个元素是否为bool类型
						values = values.subList(0, lastIdx); // 截取args values 除最后一个元素的其它元素并重新赋值给 values，即移除args最后一个元素
						map.put(fieldOrder.get(1), getValue(parser, beanFactory, lastValue)); //将 args 最后一个bool元素的 值放入以 shortcutConf 最后一个字段为键中
					}
				}
				String fieldName = fieldOrder.get(0);
				map.put(fieldName, values.stream().map(value -> getValue(parser, beanFactory, value))
						.collect(Collectors.toList())); // 将 args 中 除最后一个元素的values 解析并合并成list，将这个list 放入 shortcutConf 字段集合第一个元素做键的值
				return map;
			}
		};

		public abstract Map<String, Object> normalize(Map<String, String> args, ShortcutConfigurable shortcutConf,
				SpelExpressionParser parser, BeanFactory beanFactory);

	}

}
