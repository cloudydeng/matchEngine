package com.matching.core.domain;

public enum TimeInForce {
    GTC,        // Good-Til-Canceled （默认，一直挂着直到撤单）
    IOC,        // Immediate-Or-Cancel （吃多少是多少，剩下的取消）
    FOK,        // Fill-Or-Kill （要么全成，要么全撤）
    POST_ONLY
}
