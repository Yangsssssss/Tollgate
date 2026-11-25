package io.tollgate.metering;

/**
 * 一条费率：某模型每 1K token 的单价，单位 micros（百万分之一元）。
 * 与 tariff 表行一一对应，随 CatalogSnapshot 进入内存。
 */
public record TariffRate(long promptMicrosPer1k, long completionMicrosPer1k) {

    public TariffRate {
        if (promptMicrosPer1k < 0 || completionMicrosPer1k < 0) {
            throw new IllegalArgumentException("费率不能为负");
        }
    }
}
