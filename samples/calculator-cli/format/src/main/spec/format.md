# Format

Writes a computed number for people to read.

## API

```kotlin
package dev.graidle.demo.calc.format

fun formatNumber(value: Double): String
```

## Behavior

1. A whole number has no fractional part: `7.0` gives `7`, and `-3.0` gives `-3`.
2. Other numbers are rounded half-up to at most 10 decimal places, and trailing zeros are removed: `0.1 + 0.2` gives `0.3`, `1.0 / 3` gives `0.3333333333`, and `2.0 / 3` gives `0.6666666667`.
3. The result never uses exponent notation: `1e21` gives `1000000000000000000000`, and `1e-11` gives `0`.
4. Negative zero, and any value that rounds to zero, gives `0`, never `-0`.
5. The decimal separator is always `.`, whatever the default locale is.
6. The behavior for infinite values and NaN is not specified: the caller never passes them.
