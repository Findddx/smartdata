package io.smartdata.foundation.runtime.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles

/** A supported deployment is explicit; missing production beans are not a readiness mechanism. */
@Configuration
class RuntimeModeSafetyGate(environment: Environment) {
    init {
        val local = environment.acceptsProfiles(Profiles.of("local"))
        val enterprise = environment.acceptsProfiles(Profiles.of("enterprise"))
        require(!(local && (enterprise || environment.acceptsProfiles(Profiles.of("production"))))) {
            "本机演示身份不能与 enterprise 或 production 同时启用"
        }
        if (!local) {
            require(enterprise && environment.acceptsProfiles(Profiles.of("postgres"))) {
                "企业工作区需要显式启用 enterprise,postgres；默认生产配置不提供演示身份或仿真执行器"
            }
            require(environment.getProperty("smartdata.persistence.enabled", Boolean::class.java, false)) {
                "企业工作区必须启用真实 PostgreSQL 持久化，禁止回退文件或内存"
            }
        }
    }
}
