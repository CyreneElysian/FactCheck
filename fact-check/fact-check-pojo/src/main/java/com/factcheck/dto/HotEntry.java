package com.factcheck.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 热度榜条目：记录 ID 与其在 Redis ZSET 中的分值
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HotEntry {

    private Long recordId;

    private Double score;
}
