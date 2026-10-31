package com.agentflow.identity;

import java.util.UUID;

public record CurrentUser(UUID id, String email, boolean superAdmin) { }
