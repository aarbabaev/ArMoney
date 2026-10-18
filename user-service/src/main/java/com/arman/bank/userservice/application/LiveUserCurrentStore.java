package com.arman.bank.userservice.application;
import java.util.Map;
public interface LiveUserCurrentStore {
    Map<String,Object> current(String writer,Map<String,Object> request);
    final class Rejected extends RuntimeException {
        private final int status;
        public Rejected(int status){super("Live User request rejected",null,false,false);this.status=status;}
        public int status(){return status;}
    }
}
