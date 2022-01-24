package com.qianliusi.slothim.local;

import com.qianliusi.slothim.HttpService;
import io.vertx.core.Vertx;

public class Main {

  public static void main(String[] args) {
    Vertx vertx = Vertx.vertx();
    vertx.deployVerticle(HttpService.class.getName());
  }
}
