package com.hmdp.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
@NoArgsConstructor
@AllArgsConstructor
@Data
public class ScrollList {
    private List<?> list;
    private Long max;
    private Integer offset;
}
