package com.qianliusi.slothim.enums;

/**
 * 枚举类请实现此接口
 *
 * @param <T> code的类型
 * @author qianliusi
 */
public interface EnumService<T> {
	/**
	 * 枚举code
	 *
	 * @return code
	 */
	T code();

	/**
	 * 枚举描述
	 *
	 * @return desc
	 */
	String desc();
}
