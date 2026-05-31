"""BLE protocol constants, packet building, and deframing."""

from typing import Optional

# ── UUIDs ────────────────────────────────────────────────────────────────────
SERVICE_MAIN     = "00006006-0000-1000-8000-00805f9b34fb"
CHAR_WRITE_8001  = "00008001-0000-1000-8000-00805f9b34fb"
CHAR_NOTIFY_8002 = "00008002-0000-1000-8000-00805f9b34fb"
CHAR_WRITE_8003  = "00008003-0000-1000-8000-00805f9b34fb"
CHAR_NOTIFY_8004 = "00008004-0000-1000-8000-00805f9b34fb"
CHAR_8005        = "00008005-0000-1000-8000-00805f9b34fb"
CCCD_UUID        = "00002902-0000-1000-8000-00805f9b34fb"
SERVICE_APOLLO   = "00001530-0000-1000-8000-00805f9b34fb"

# ── Protocol constants ────────────────────────────────────────────────────────
FLAG_START = 0x6F
FLAG_END   = 0x8F

ACTION_CHECK          = 0x70
ACTION_SET            = 0x71
ACTION_CHECK_RESPONSE = 0x80
ACTION_SET_RESPONSE   = 0x81

ACTION_NAMES = {
    ACTION_CHECK:          "CHECK",
    ACTION_SET:            "SET",
    ACTION_CHECK_RESPONSE: "CHECK_RESPONSE",
    ACTION_SET_RESPONSE:   "SET_RESPONSE",
}

# All command codes from BluetoothCommandConstant.smali
CMDS = {
    "RESPONSE":                    0x01,
    "WATCH_ID":                    0x02,
    "DEVICE_VERSION":              0x03,
    "DATETIME":                    0x04,
    "TIME_SURFACE_SETTING":        0x05,
    "PRIMARY_SURFACE_DISPLAY":     0x06,
    "SCREEN_BRIGHTNESS":           0x07,
    "BATTERY_POWER":               0x08,
    "VOLUME":                      0x09,
    "SHOCK_MODE":                  0x0A,
    "LANGUAGE":                    0x0B,
    "UNIT":                        0x0C,
    "RESTORE_FACTORY":             0x0D,
    "UPGRADE_MODE":                0x0E,
    "SHOCK_STRENGTH":              0x10,
    "MAIN_ALARM_BACKGROUND_COLOR": 0x11,
    "WORK_MODE":                   0x12,
    "BRIGHT_SCREEN_TIME":          0x13,
    "SNOOZE":                      0x14,
    "DO_NOT_DISTURB":              0x15,
    "TRAN_SPEED":                  0x16,
    "POWER_OFF_MODE":              0x17,
    "TIME_ZONE":                   0x18,
    "NOTIFICATIONS_TEXT_SIZE":     0x19,
    "CONTROL_DEVICE":              0x1A,
    "CUSTOMIZE_WATCH_FACE_EX":     0x1E,
    "CUSTOMIZE_WATCH_FACE_PRO":    0x1F,
    "CUSTOMIZE_WATCH_FACE_SET":    0x20,
    "ANALOG_MODE":                 0x21,
    "WEATHER_SETTING":             0x22,
    "FIND_DEVICE":                 0x23,
    "BRIGHT_SCREEN_TIME_EX":       0x25,
    "EVENT_TIME_INTERVAL":         0x26,
    "APP_SETTING":                 0x2A,
    "CUSTOMIZE_SCALE_ACTION":      0x2D,
    "CUSTOMIZE_BUTTON":            0x2E,
    "TIME_PERIOD_BRIGHTNESS":      0x2F,
    "USER_INFO":                   0x30,
    "USAGE_HABITS":                0x31,
    "USER_NAME":                   0x32,
    "GOAL":                        0x50,
    "SPORT_SLEEP_MODE":            0x51,
    "TOTAL_SPORT_SLEEP_COUNT":     0x52,
    "DELETE_SPORT_DATA":           0x53,
    "GET_SPORT_DATA":              0x54,
    "DELETE_SLEEP_DATA":           0x55,
    "GET_SLEEP_DATA":              0x56,
    "DEVICE_DISPLAY_DATA":         0x57,
    "AUTO_SLEEP":                  0x58,
    "TOTAL_HEART_RATE_COUNT":      0x59,
    "DELETE_HEART_RATE_DATA":      0x5A,
    "GET_HEART_RATE_DATA":         0x5B,
    "AUTO_HEART_RATE":             0x5C,
    "HEART_RATE_ALARM_THRESHOLD":  0x5D,
    "INACTIVITY_ALERT":            0x5E,
    "GET_MOOD_DATA":               0x5F,
    "CALORIES_TYPE":               0x60,
    "GET_HEART_RATE_DATA_EX":      0x61,
    "TOTAL_BLOOD_PRESSURE_COUNT":  0x62,
    "DELETE_BLOOD_PRESSURE_DATA":  0x63,
    "GET_BLOOD_PRESSURE_DATA":     0x64,
    "TOTAL_REAL_TIME_SPORT_DATA_COUNT": 0x66,
    "GET_REAL_TIME_SPORT_DATA":    0x67,
    "DELETE_REAL_TIME_SPORT_DATA": 0x68,
    "GPS":                         0x6A,
    "GET_GPS_DATA":                0x6B,
    "GET_GPS_DATA_EX":             0x6D,
    "PHONE_NAME_PUSH":             0x70,
    "SMS_PUSH":                    0x71,
    "MSG_COUNT_PUSH":              0x72,
    "SOCIAL_PUSH":                 0x73,
    "EMAIL_PUSH":                  0x74,
    "SCHEDULE_PUSH":               0x75,
    "SOCIAL_EX_PUSH":              0x76,
    "WEATHER_PUSH":                0x77,
    "WEATHER_PUSH_EX":             0x78,
    "SOCIAL_NEW_PUSH":             0x79,
    "SWITCH_SETTING":              0x90,
}

CMD_NAMES = {v: k for k, v in CMDS.items()}
COMMAND_REPEAT_ECHO = bytes([0x6F, 0x01, 0x81, 0x02, 0x00, 0x01, 0x00, 0x8F])


# ── Packet building / parsing ─────────────────────────────────────────────────

def build_packet(cmd_byte: int, action: int, payload: bytes = b"") -> bytes:
    """Frame: [0x6F][cmd][action][lenLo][lenHi][payload...][0x8F]"""
    n = len(payload)
    return bytes([FLAG_START, cmd_byte, action, n & 0xFF, (n >> 8) & 0xFF]) + payload + bytes([FLAG_END])


class PacketDeframer:
    """Reassembles frames that span multiple MTU chunks (length-guided)."""

    def __init__(self):
        self.buf = bytearray()
        self.in_frame = False
        self.expected_size = -1

    def feed(self, chunk: bytes) -> Optional[dict]:
        for b in chunk:
            if not self.in_frame:
                if b == FLAG_START:
                    self.buf = bytearray([b])
                    self.in_frame = True
                    self.expected_size = -1
            else:
                self.buf.append(b)
                n = len(self.buf)
                if self.expected_size < 0 and n == 5:
                    payload_len = self.buf[3] | (self.buf[4] << 8)
                    if payload_len > 512:
                        print(f"  [deframer] oversized payload={payload_len}, resyncing")
                        self.in_frame = False
                        self.buf = bytearray()
                        continue
                    self.expected_size = payload_len + 6
                if self.expected_size > 0 and n == self.expected_size:
                    self.in_frame = False
                    frame = bytes(self.buf)
                    self.buf = bytearray()
                    return _parse_frame(frame)
        return None


def _parse_frame(frame: bytes) -> Optional[dict]:
    if len(frame) < 6 or frame[-1] != FLAG_END:
        print(f"  [parse] bad frame: {frame.hex()}")
        return None
    cmd_b   = frame[1]
    act_b   = frame[2]
    plen    = frame[3] | (frame[4] << 8)
    payload = frame[5:5 + plen]
    return {
        "cmd_byte":    cmd_b,
        "action_byte": act_b,
        "cmd_name":    CMD_NAMES.get(cmd_b, f"0x{cmd_b:02X}"),
        "action_name": ACTION_NAMES.get(act_b, f"0x{act_b:02X}"),
        "payload":     payload,
        "raw":         frame,
    }


def fmt_packet(p: dict) -> str:
    pay = p["payload"].hex() if p["payload"] else "(empty)"
    return f"{p['cmd_name']} / {p['action_name']} payload=[{pay}]  raw=[{p['raw'].hex()}]"


def matches(pkt: dict, sent_cmd: int, sent_action: int) -> bool:
    """True if pkt is the expected response to a sent command."""
    if pkt["action_byte"] == ACTION_CHECK_RESPONSE:
        return pkt["cmd_byte"] == sent_cmd
    if pkt["action_byte"] == ACTION_SET_RESPONSE:
        if pkt["cmd_byte"] == sent_cmd:
            return True
        if pkt["cmd_byte"] == 0x01 and pkt["payload"] and pkt["payload"][0] == sent_cmd:
            return True
    return False
