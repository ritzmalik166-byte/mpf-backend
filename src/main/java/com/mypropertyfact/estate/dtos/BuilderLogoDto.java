package com.mypropertyfact.estate.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Builder name + logo for marquees and cards. Field names match the {@code Builder} entity JSON. */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class BuilderLogoDto {
    private int id;
    private String builderName;
    private String slugUrl;
    private String builderLogo;
}
