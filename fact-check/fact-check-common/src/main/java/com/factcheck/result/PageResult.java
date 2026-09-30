package com.factcheck.result;

import java.io.Serializable;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 分页查询统一返回结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PageResult implements Serializable {
    private long total;
    private List records;
}
