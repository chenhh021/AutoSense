package com.chh.autosense.ai.model;
public record DeviceBasicInfo(Long id, String name, String sn, String deviceType, String deviceModel,
                              boolean online, String description) implements java.io.Serializable { }
