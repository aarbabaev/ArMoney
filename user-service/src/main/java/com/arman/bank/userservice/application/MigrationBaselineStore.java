package com.arman.bank.userservice.application;
import com.arman.bank.userservice.domain.MigrationBaseline;
import java.util.Map;
public interface MigrationBaselineStore {
 Map<String,Object> stage(String writer,MigrationBaseline.Value command);
 Map<String,Object> publish(String writer,MigrationBaseline.Value command);
 Map<String,Object> result(String writer,MigrationBaseline.Value command);
 Map<String,Object> resolve(String writer,MigrationBaseline.Value command);
 final class Rejected extends RuntimeException {
  private final int status;
  public Rejected(int status) { super("Migration rejected or unavailable"); this.status=status; }
  public int status() { return status; }
 }
}
