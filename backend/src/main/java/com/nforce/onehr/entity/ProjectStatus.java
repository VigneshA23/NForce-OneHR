package com.nforce.onehr.entity;

/** US-B01: projects are never deleted; CLOSED (or ON_HOLD) just stops new allocations — only ACTIVE accepts them. */
public enum ProjectStatus { ACTIVE, ON_HOLD, CLOSED }
