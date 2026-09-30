package com.factcheck.constant;

/**
 * 热度分权重常量
 */
public class HotScoreConstant {
    public static final double DETECT_SCORE = 1.0;
    public static final double VIEW_SCORE = 0.5;
    public static final double APPEAL_SCORE = 3.0;
    public static final double MANUAL_SCORE = 2.0;
    public static final double AUTHORITY_SCORE = 5.0;
    public static final double DECAY_FACTOR = 0.85;
    public static final double MIN_HOT_SCORE = 1.0;
}
