import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_hooks/flutter_hooks.dart';
import '../services/zebra_service.dart';

// Hook: rfid_screen converted to HookWidget
// Source: lib/screens/rfid_screen.dart
class RfidScreen extends HookWidget {
  const RfidScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final service = ZebraService.instance;

    // State management with useState
    final tags = useState<List<TagItem>>([]);
    final logEntries = useState<List<_LogEntry>>([]);
    final status = useState<String>('Initializing...');
    final statusColor = useState<Color>(Colors.grey.shade200);
    final scanning = useState<bool>(false);
    final logExpanded = useState<bool>(true);
    final powerIndex = useState<int>(0);
    final maxPowerIndex = useState<int>(270);

    // useRef for mutable values (ScrollController, Timer)
    final scrollController = useRef(ScrollController());
    final clearTimer = useState<Timer?>(null);

    // useEffect for lifecycle - setup stream subscription
    useEffect(() {
      // Load max power on mount
      service.getMaxPowerIndex().then((max) {
        powerIndex.value = max;
        maxPowerIndex.value = max;
      });

      // Subscribe to events
      final sub = service.events.listen((event) {
        _onEvent(event, tags, logEntries, status, statusColor, scanning, clearTimer);
      });

      // Cleanup on unmount
      return () {
        sub.cancel();
        clearTimer.value?.cancel();
        scrollController.value.dispose();
      };
    }, []);

    // Auto-scroll log when new entries added
    useEffect(() {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (scrollController.value.hasClients) {
          scrollController.value.animateTo(scrollController.value.position.maxScrollExtent, duration: const Duration(milliseconds: 200), curve: Curves.easeOut);
        }
      });
    }, [logEntries.value.length]);

    void startInventory() async {
      scanning.value = true;
      tags.value = [];
      _addLog(logEntries, 'Start inventory (button)', Colors.blue);
      await service.startRfidInventory();
    }

    void stopInventory() async {
      clearTimer.value?.cancel();
      scanning.value = false;
      _addLog(logEntries, 'Stop inventory (button)', Colors.blue);
      await service.stopRfidInventory();
    }

    void onPowerChanged(int index) async {
      powerIndex.value = index;
      await service.setPower(index);
    }

    return Scaffold(
      appBar: AppBar(
        title: const Text('RFID Inventory'),
        actions: [
          Center(
            child: Padding(
              padding: const EdgeInsets.only(right: 12),
              child: Text('${tags.value.length} tags', style: const TextStyle(fontSize: 14)),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          // ── Status bar ─────────────────────────────────────────────────
          AnimatedContainer(
            duration: const Duration(milliseconds: 300),
            width: double.infinity,
            color: statusColor.value,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            child: Text(status.value, style: const TextStyle(fontSize: 12), overflow: TextOverflow.ellipsis),
          ),

          // ── Power slider ───────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: Row(
              children: [
                const Text('Power:', style: TextStyle(fontSize: 13)),
                Expanded(
                  child: Slider(
                    value: powerIndex.value.toDouble(),
                    min: 0,
                    max: maxPowerIndex.value.toDouble(),
                    divisions: maxPowerIndex.value > 0 ? maxPowerIndex.value : 1,
                    onChanged: scanning.value ? null : (v) => onPowerChanged(v.round()),
                  ),
                ),
                SizedBox(
                  width: 36,
                  child: Text('${powerIndex.value}', style: const TextStyle(fontSize: 13), textAlign: TextAlign.right),
                ),
              ],
            ),
          ),

          // ── Start / Stop ───────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: Row(
              children: [
                Expanded(
                  child: ElevatedButton(onPressed: scanning.value ? null : startInventory, child: const Text('Start')),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(backgroundColor: Colors.red.shade400, foregroundColor: Colors.white),
                    onPressed: scanning.value ? stopInventory : null,
                    child: const Text('Stop'),
                  ),
                ),
              ],
            ),
          ),

          // ── Trigger hint ───────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.only(bottom: 4),
            child: Text(
              scanning.value ? '🔴 Scanning… (trigger release = stop)' : 'Start button or physical trigger starts inventory',
              style: TextStyle(fontSize: 11, color: scanning.value ? Colors.red : Colors.grey),
            ),
          ),

          const Divider(height: 1),

          // ── Tag list (flexible) ────────────────────────────────────────
          Expanded(
            flex: 3,
            child: tags.value.isEmpty
                ? Center(
                    child: Text(scanning.value ? 'Scanning for tags…' : 'No tags read yet', style: const TextStyle(color: Colors.grey)),
                  )
                : ListView.builder(
                    itemCount: tags.value.length,
                    itemBuilder: (_, i) {
                      final tag = tags.value[i];
                      return ListTile(
                        dense: true,
                        leading: const Icon(Icons.nfc, size: 18),
                        title: Text(tag.epc, style: const TextStyle(fontFamily: 'monospace', fontSize: 13)),
                        trailing: Text('${tag.rssi} dBm', style: const TextStyle(fontSize: 12)),
                      );
                    },
                  ),
          ),

          // ── Event log (expandable) ─────────────────────────────────────
          const Divider(height: 1),
          InkWell(
            onTap: () => logExpanded.value = !logExpanded.value,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Row(
                children: [
                  Icon(logExpanded.value ? Icons.expand_more : Icons.expand_less, size: 16),
                  const SizedBox(width: 6),
                  Text('Event Log (${logEntries.value.length})', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
                  const Spacer(),
                  if (logEntries.value.isNotEmpty)
                    GestureDetector(
                      onTap: () => logEntries.value = [],
                      child: const Text('clear', style: TextStyle(fontSize: 11, color: Colors.grey)),
                    ),
                ],
              ),
            ),
          ),
          if (logExpanded.value)
            Expanded(
              flex: 2,
              child: logEntries.value.isEmpty
                  ? const Center(
                      child: Text('No events yet', style: TextStyle(color: Colors.grey, fontSize: 12)),
                    )
                  : ListView.builder(
                      controller: scrollController.value,
                      itemCount: logEntries.value.length,
                      itemBuilder: (_, i) {
                        final entry = logEntries.value[i];
                        return Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 1),
                          child: Text(
                            entry.message,
                            style: TextStyle(fontSize: 11, fontFamily: 'monospace', color: entry.color),
                          ),
                        );
                      },
                    ),
            ),
        ],
      ),
    );
  }
}

// Event handler function
// Hook: _onEvent processes ZebraEvent and updates state via ValueNotifier
void _onEvent(
  ZebraEvent event,
  ValueNotifier<List<TagItem>> tagsNotifier,
  ValueNotifier<List<_LogEntry>> logEntries,
  ValueNotifier<String> status,
  ValueNotifier<Color> statusColor,
  ValueNotifier<bool> scanning,
  ValueNotifier<Timer?> clearTimer,
) {
  const clearTimeout = Duration(seconds: 2);

  if (event is RfidConnectedEvent) {
    status.value = event.message.isEmpty ? 'Connected' : event.message;
    statusColor.value = Colors.green.shade100;
    _addLog(logEntries, 'RFID: ${event.message}', Colors.green.shade700);
  } else if (event is RfidErrorEvent) {
    status.value = event.message;
    statusColor.value = Colors.red.shade100;
    scanning.value = false;
    _addLog(logEntries, 'RFID HATA: ${event.message}', Colors.red);
  } else if (event is RfidDisconnectedEvent) {
    status.value = 'Reader disconnected';
    statusColor.value = Colors.red.shade100;
    scanning.value = false;
    _addLog(logEntries, 'RFID: disconnected', Colors.red);
  } else if (event is TagDataEvent) {
    // REMOVED: Tags were being cleared every 2 seconds - now tags persist until manually cleared
    // Tags accumulate as they are read, user can see all scanned tags
    _mergeTags(tagsNotifier, event.tags);
    final epcList = event.tags.map((t) => t.epc).join(', ');
    final shortEpc = epcList.length > 40 ? '${epcList.substring(0, 40)}…' : epcList;
    _addLog(logEntries, 'Tags: ${event.tags.length} read ($shortEpc)', Colors.green.shade700);
  } else if (event is TriggerPressEvent) {
    scanning.value = event.pressed;
    _addLog(logEntries, event.pressed ? 'Trigger PRESSED → inventory started' : 'Trigger RELEASED → inventory stopped', Colors.blue.shade700);
  } else if (event is MessageEvent) {
    status.value = event.message;
    statusColor.value = event.message.toLowerCase().contains('error') || event.message.toLowerCase().contains('fail')
        ? Colors.orange.shade100
        : Colors.grey.shade200;
    _addLog(logEntries, 'MSG: ${event.message}', Colors.orange.shade800);
  } else if (event is BarcodeDataEvent) {
    _addLog(logEntries, 'BARCODE: ${event.data} (${event.label.isNotEmpty ? event.label : 'sym'})', Colors.purple.shade700);
  }
}

void _resetClearTimer(ValueNotifier<Timer?> clearTimer, Duration timeout, void Function() onClear) {
  clearTimer.value?.cancel();
  clearTimer.value = Timer(timeout, onClear);
}

void _mergeTags(ValueNotifier<List<TagItem>> tagsNotifier, List<TagItem> incoming) {
  final currentTags = List<TagItem>.from(tagsNotifier.value);
  for (final tag in incoming) {
    final idx = currentTags.indexWhere((t) => t.epc == tag.epc);
    if (idx >= 0) {
      currentTags[idx] = tag;
    } else {
      currentTags.add(tag);
    }
  }
  tagsNotifier.value = currentTags;
}

void _addLog(ValueNotifier<List<_LogEntry>> logEntries, String msg, Color color) {
  final now = TimeOfDay.now();
  final ts = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${DateTime.now().second.toString().padLeft(2, '0')}';
  logEntries.value = [...logEntries.value, _LogEntry('$ts  $msg', color)];
}

class _LogEntry {
  final String message;
  final Color color;
  _LogEntry(this.message, this.color);
}
