package com.mypropertyfact.estate.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Navigation / filter payload for a city. Field names match {@link CityDetailDto}. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CityNavDto {
    private int id;
    private String cityName;
    private String slugURL;
    private Boolean isActive;
    private String stateName;
}
