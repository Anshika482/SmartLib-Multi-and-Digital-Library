package com.smartlib.platform;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "platform_settings")
@Getter
@Setter
public class PlatformSetting {

    @Id
    private String settingKey;

    private String settingValue;
    private Long updatedBy;
    private Instant updatedAt;
}
