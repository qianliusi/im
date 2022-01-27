package com.qianliusi.slothim.local;

import com.qianliusi.slothim.HttpService;
import com.qianliusi.slothim.MatchUserVerticle;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Vertx;

public class Main {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(HttpService.class.getName());
    DeploymentOptions opts = new DeploymentOptions().setInstances(2).setWorker(true);
    vertx.deployVerticle(MatchUserVerticle.class.getName(), opts);
  }
}
