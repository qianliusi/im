package com.qianliusi.slothim.store;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.Shareable;
import io.vertx.core.shareddata.impl.ClusterSerializable;

public class MsgUser implements ClusterSerializable, Shareable {

	private String token;
	private String state;//idle,matching,matched
	private String partner;

	public MsgUser() {
	}

	public MsgUser(String state) {
		this.state = state;
	}

	public MsgUser(String token, String state) {
		this.token = token;
		this.state = state;
	}

	public MsgUser(String token, String state, String partner) {
		this.token = token;
		this.state = state;
		this.partner = partner;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getPartner() {
		return partner;
	}

	public void setPartner(String partner) {
		this.partner = partner;
	}

	@Override
	public String toString() {
		return "MsgUser{" + "token='" + token + '\'' + ", state='" + state + '\'' + ", partner='" + partner + '\'' + '}';
	}

	@Override
	public void writeToBuffer(Buffer buffer) {
		Json.encodeToBuffer(new JsonObject(BeanUtil.beanToMap(this))).writeToBuffer(buffer);
	}

	@Override
	public int readFromBuffer(int pos, Buffer buffer) {
		JsonObject jsonObject = new JsonObject();
		int read = jsonObject.readFromBuffer(pos, buffer);
		BeanUtil.copyProperties(JSON.parseObject(jsonObject.toString(), MsgUser.class),this);
		return read;
	}

	@Override
	public Shareable copy() {
		return BeanUtil.copyProperties(this,MsgUser.class);
	}
}
