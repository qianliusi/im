package com.qianliusi.slothim;

import cn.hutool.core.lang.Tuple;
import com.qianliusi.slothim.enums.UserStateEnum;
import com.qianliusi.slothim.store.MsgUser;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonObject;
import io.vertx.core.shareddata.AsyncMap;
import io.vertx.core.shareddata.SharedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MatchUserVerticle extends AbstractVerticle {
	private static Logger logger = LoggerFactory.getLogger(MatchUserVerticle.class);

	@Override
	public void start() {
		EventBus bus = vertx.eventBus();
		bus.consumer("matchUser", this::matchUser);
	}

	public Future<AsyncMap<String, MsgUser>> getOnlineUser() {
		// 取Websocket和token之间的对应关系
		SharedData sd = vertx.sharedData();
		if(vertx.isClustered()) {
			return sd.getClusterWideMap("chatUser");
		}
		return sd.getAsyncMap("chatUser");
	}

	private boolean validMatch(MsgUser u, String token) {
		return !u.getId().equals(token) && UserStateEnum.matching.code().equals(u.getState());
	}

	private void matchUser(Message<String> message) {
		JsonObject json = new JsonObject();//匹配成功
		String userId = message.body();
		Promise<Tuple> userIdPromise = doMatchUser(userId, 30);//匹配次数
		userIdPromise.future().onSuccess(re->{
			json.put("state", re.get(0));
			json.put("result", re.get(1));
			message.reply(json);
		});
	}

	private Promise<Tuple> doMatchUser(String userId, int n) {
		Promise<Tuple> promise = Promise.promise();
		if(n < 1) {
			promise.complete(new Tuple("fail","matchTimeout"));
			return promise;
		}
		n--;
		try {
			Thread.sleep(1000);
		} catch(InterruptedException e) {
			logger.error("doMatchUser InterruptedException",e);
			promise.complete(new Tuple("fail",e.getMessage()));
			return promise;
		}
		Future<AsyncMap<String, MsgUser>> onlineUser = getOnlineUser();
		int finalN = n;
		onlineUser.onSuccess(asyncMap -> {
			//检查自己有没有被别人匹配
			asyncMap.get(userId).onSuccess(matchingUser -> {
				if(matchingUser == null) {
					Tuple tuple = new Tuple("fail", "userOffline");
					promise.complete(tuple);
					logger.info("doMatchUser [{}]",tuple);
				} else {
					if(UserStateEnum.matched.code().equals(matchingUser.getState())) {
						Tuple tuple = new Tuple("success", matchingUser.getPartner());
						promise.complete(tuple);
						logger.info("doMatchUser [{}]",tuple);
					} else {
						asyncMap.values().onSuccess(list -> {
							MsgUser matchedUser = list.stream().filter(a -> validMatch(a, userId)).findAny().orElse(null);
							//无配对中用户
							if(matchedUser == null) {
								logger.info("not find other matching user");
								doMatchUser(userId, finalN).future().onSuccess(promise::complete);
							} else {
								matchingUser.setState(UserStateEnum.matched.code());
								matchingUser.setPartner(matchedUser.getId());
								asyncMap.put(matchingUser.getId(), matchingUser);
								matchedUser.setState(UserStateEnum.matched.code());
								matchedUser.setPartner(matchingUser.getId());
								asyncMap.put(matchedUser.getId(), matchedUser);
								Tuple tuple = new Tuple("success", matchedUser.getId());
								promise.complete(tuple);
								logger.info("doMatchUser [{}]",tuple);
							}
						});
					}
				}
			});
		}).onFailure(e-> doMatchUser(userId,finalN).future().onSuccess(promise::complete));
		return promise;
	}
}