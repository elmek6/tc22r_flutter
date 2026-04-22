import 'dart:async';
import 'package:flutter/services.dart';

// Typed event from the native side
sealed class ZebraEvent {}

class RfidConnectedEvent extends ZebraEvent {
  final String message;
  RfidConnectedEvent(this.message);
}

class RfidDisconnectedEvent extends ZebraEvent {}

class RfidErrorEvent extends ZebraEvent {
  final String message;
  RfidErrorEvent(this.message);
}

class TagDataEvent extends ZebraEvent {
  final List<TagItem> tags;
  TagDataEvent(this.tags);
}

class BarcodeDataEvent extends ZebraEvent {
  final String data;
  final int symbology;
  final String label; // DataWedge sends string like "LABEL-TYPE-EAN128"; DSC SDK sends int
  BarcodeDataEvent(this.data, this.symbology, {this.label = ''});
}

class TriggerPressEvent extends ZebraEvent {
  final bool pressed;
  TriggerPressEvent(this.pressed);
}

class MessageEvent extends ZebraEvent {
  final String message;
  MessageEvent(this.message);
}

class TagItem {
  final String epc;
  final int rssi;
  TagItem({required this.epc, required this.rssi});
}

// Singleton service that wraps the Platform Channel to native Zebra code.
// Mirrors the static rfidHandler / scannerHandler pattern from MainApplication.java.
class ZebraService {
  ZebraService._();
  static final ZebraService instance = ZebraService._();

  static const _method = MethodChannel('com.rfidsample/channel');
  static const _event = EventChannel('com.rfidsample/events');

  final _controller = StreamController<ZebraEvent>.broadcast();
  Stream<ZebraEvent> get events => _controller.stream;

  StreamSubscription<dynamic>? _nativeSub;

  // Call once after the EventChannel stream is ready (from main.dart)
  Future<void> initialize() async {
    _nativeSub ??= _event.receiveBroadcastStream().listen(
      _onNativeEvent,
      onError: (e) => _controller.addError(e),
    );
    await _method.invokeMethod('initialize');
  }

  void _onNativeEvent(dynamic raw) {
    if (raw is! Map) return;
    final map = Map<String, dynamic>.from(raw);
    switch (map['type'] as String?) {
      case 'rfidConnected':
        _controller.add(RfidConnectedEvent(map['message'] as String? ?? ''));
      case 'rfidDisconnected':
        _controller.add(RfidDisconnectedEvent());
      case 'rfidError':
        _controller.add(RfidErrorEvent(map['message'] as String? ?? ''));
      case 'tagData':
        final rawTags = map['tags'] as List? ?? [];
        final tags = rawTags.map((t) {
          final m = Map<String, dynamic>.from(t as Map);
          return TagItem(
            epc: m['epc'] as String? ?? '',
            rssi: int.tryParse(m['rssi'] as String? ?? '0') ?? 0,
          );
        }).toList();
        _controller.add(TagDataEvent(tags));
      case 'barcodeData':
        // DSC SDK sends symbology as int; DataWedge sends it as String ("LABEL-TYPE-EAN128")
        final symRaw = map['symbology'];
        final symbology = symRaw is int ? symRaw : int.tryParse(symRaw?.toString() ?? '') ?? 0;
        final symLabel = symRaw is String ? symRaw : 'sym:$symRaw';
        _controller.add(BarcodeDataEvent(
          map['data'] as String? ?? '',
          symbology,
          label: symLabel,
        ));
      case 'triggerPress':
        _controller.add(TriggerPressEvent(map['pressed'] as bool? ?? false));
      case 'message':
        _controller.add(MessageEvent(map['message'] as String? ?? ''));
    }
  }

  Future<void> startRfidInventory() =>
      _method.invokeMethod('startRfidInventory');

  Future<void> stopRfidInventory() =>
      _method.invokeMethod('stopRfidInventory');

  Future<void> startBarcodeScan() =>
      _method.invokeMethod('startBarcodeScan');

  Future<void> stopBarcodeScan() =>
      _method.invokeMethod('stopBarcodeScan');

  // powerIndex: raw index into the reader's discrete power table (0 = min)
  Future<void> setPower(int powerIndex) =>
      _method.invokeMethod('setPower', {'powerIndex': powerIndex});

  Future<int> getMaxPowerIndex() async {
    final result = await _method.invokeMethod<int>('getMaxPowerIndex');
    return result ?? 270;
  }

  Future<bool> isConnected() async {
    final result = await _method.invokeMethod<bool>('isConnected');
    return result ?? false;
  }

  void dispose() {
    _nativeSub?.cancel();
    _controller.close();
  }
}
