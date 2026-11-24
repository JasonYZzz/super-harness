package io.superharness.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import io.superharness.domain.Problem;
import java.util.List;
import java.util.Map;

@Component
class ModelPrices {
    record Rate(String currency, BigDecimal inputPerMillion, BigDecimal outputPerMillion) {
        Rate {
            if (currency == null
                    || !currency.matches("[A-Z]{3}")
                    || !valid(inputPerMillion)
                    || !valid(outputPerMillion))
                throw new IllegalArgumentException("Invalid SH_MODEL_PRICING");
        }

        private static boolean valid(BigDecimal value) {
            return value != null
                    && value.signum() >= 0
                    && value.scale() <= 6
                    && value.compareTo(new BigDecimal("10000000000")) < 0;
        }
    }

    final Map<String, Rate> prices;
    final List<String> allowed;
    final String defaultModel;

    /** Loads the model catalog and rates. Legacy environment settings override YAML defaults. */
    ModelPrices(Environment env, ObjectMapper mapper) throws java.io.IOException {
        String legacyAllowed = env.getProperty("SH_ALLOWED_MODELS");
        List<String> configured = legacyAllowed == null
                ? Binder.get(env).bind("sh.models.allowed", Bindable.listOf(String.class))
                        .orElse(List.of("local-echo"))
                : List.of(legacyAllowed.split(","));
        allowed =
                configured.stream()
                        .map(String::trim)
                        .filter(id -> !id.isEmpty())
                        .distinct()
                        .toList();
        if (allowed.isEmpty()) throw new IllegalArgumentException("No allowed models configured");
        defaultModel = env.getProperty(
                "SH_DEFAULT_MODEL",
                legacyAllowed == null
                        ? env.getProperty("sh.models.default-model", allowed.getFirst())
                        : allowed.getFirst());
        if (!allowed.contains(defaultModel))
            throw new IllegalArgumentException("The default model must be in the allowed model list");
        prices =
                Map.copyOf(
                        mapper.readValue(
                                env.getProperty("SH_MODEL_PRICING", "{}"),
                                new TypeReference<Map<String, Rate>>() {}));
    }
    Map<String, Object> settings(String model) {
        if (!allowed.contains(model)) throw new Problem(400, "MODEL_NOT_ALLOWED");
        var settings = new LinkedHashMap<String, Object>();
        settings.put("model", model);
        if (prices.containsKey(model)) settings.put("pricing", prices.get(model));
        return settings;
    }

}
