package com.arman.bank.userservice.application;
import com.arman.bank.userservice.domain.MigrationBaseline;
import java.util.Map;
public interface MigrationDecisionPort { Map<String,Object> decision(MigrationBaseline.Value stage); }
