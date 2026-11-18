# Bluetooth Functions

- `Kotlin` code is for watch

## Battery Request

```Kotlin
val request = BleProtocol.batteryLife()
```

## Set Vibration Strength

```Kotlin
val request = BleProtocol.setVibrationStrength(num)
```

`num` is set from `1-10`

## Request Vibration Strength

```Kotlin
val request = BleProtocol.setVibrationStrength(0)
```

## Request a Vibration

```Kotlin
val request = BleProtocol.vibrate(1000)
```

## Heartbeat (Verify Connection)

```Kotlin
val request = BleProtocol.heartbeat()
```
