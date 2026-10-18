package com.arman.bank.userservice.application;
import com.arman.bank.userservice.domain.*;
public interface MigrationPublicationEvidencePort { MigrationPublicationEvidence evidence(MigrationBaseline.Value stage,MigrationBaseline.Value command); }
