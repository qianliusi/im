package com.qianliusi.slothim.message;

import java.io.Serializable;

public class MsgMessage implements Serializable {
	private String token;
	private String userName;
	private String type;
	private String content;
	private String receiver;

	public MsgMessage() {
	}

	public MsgMessage(String type, String token) {
		this.type = type;
		this.token = token;
	}

	public MsgMessage(String type) {
		this.type = type;
	}

	public String getToken() {
		return token;
	}

	public void setToken(String token) {
		this.token = token;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getReceiver() {
		return receiver;
	}

	public void setReceiver(String receiver) {
		this.receiver = receiver;
	}

	@Override
	public String toString() {
		return "MsgMessage{" + "token='" + token + '\'' + ", userName='" + userName + '\'' + ", type='" + type + '\'' + ", content='" + content + '\'' + ", receiver='" + receiver + '\'' + '}';
	}
}
