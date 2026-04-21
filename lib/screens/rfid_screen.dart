import 'dart:async';
import 'package:flutter/material.dart';
import '../services/zebra_service.dart';

class RfidScreen extends StatefulWidget {
  const RfidScreen({super.key});

  @override
  State<RfidScreen> createState() => _RfidScreenState();
}

class _RfidScreenState extends State<RfidScreen> {
  final _service = ZebraService.instance;
  final _logScrollController = ScrollController();

  final List<TagItem> _tags = [];
  final List<_LogEntry> _log = [];

  String _status = 'Initializing...';
  Color _statusColor = Colors.grey.shade200;
  bool _scanning = false;
  bool _logExpanded = true;

  int _powerIndex = 0;
  int _maxPowerIndex = 270;

  Timer? _clearTimer;
  static const _clearTimeout = Duration(seconds: 2);

  StreamSubscription<ZebraEvent>? _sub;

  @override
  void initState() {
    super.initState();
    _loadMaxPower();
    _sub = _service.events.listen(_onEvent);
  }

  Future<void> _loadMaxPower() async {
    final max = await _service.getMaxPowerIndex();
    setState(() {
      _maxPowerIndex = max;
      _powerIndex = max;
    });
  }

  void _addLog(String msg, {Color? color}) {
    final now = TimeOfDay.now();
    final ts = '${now.hour.toString().padLeft(2, '0')}:${now.minute.toString().padLeft(2, '0')}:${DateTime.now().second.toString().padLeft(2, '0')}';
    setState(() => _log.add(_LogEntry('$ts  $msg', color ?? Colors.black87)));
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_logScrollController.hasClients) {
        _logScrollController.animateTo(
          _logScrollController.position.maxScrollExtent,
          duration: const Duration(milliseconds: 200),
          curve: Curves.easeOut,
        );
      }
    });
  }

  void _onEvent(ZebraEvent event) {
    switch (event) {
      case RfidConnectedEvent(:final message):
        setState(() {
          _status = message.isEmpty ? 'Connected' : message;
          _statusColor = Colors.green.shade100;
        });
        _addLog('RFID: $message', color: Colors.green.shade700);

      case RfidDisconnectedEvent():
        setState(() {
          _status = 'Reader disconnected';
          _statusColor = Colors.red.shade100;
          _scanning = false;
        });
        _addLog('RFID: disconnected', color: Colors.red);

      case TagDataEvent(:final tags):
        _resetClearTimer();
        _mergeTags(tags);
        _addLog('Tags: ${tags.length} read (${tags.map((t) => t.epc).join(', ').substring(0, tags.map((t) => t.epc).join(', ').length.clamp(0, 40))}…)');

      case TriggerPressEvent(:final pressed):
        setState(() => _scanning = pressed);
        _addLog(pressed ? 'Trigger PRESSED → inventory started' : 'Trigger RELEASED → inventory stopped',
            color: Colors.blue.shade700);

      case MessageEvent(:final message):
        setState(() {
          _status = message;
          _statusColor = message.toLowerCase().contains('error') || message.toLowerCase().contains('fail')
              ? Colors.orange.shade100
              : Colors.grey.shade200;
        });
        _addLog('MSG: $message', color: Colors.orange.shade800);

      case BarcodeDataEvent(:final data, :final label):
        _addLog('BARCODE: $data (${label.isNotEmpty ? label : 'sym'})',
            color: Colors.purple.shade700);

    }
  }

  void _resetClearTimer() {
    _clearTimer?.cancel();
    _clearTimer = Timer(_clearTimeout, _clearTagList);
  }

  void _clearTagList() => setState(() => _tags.clear());

  void _mergeTags(List<TagItem> incoming) {
    setState(() {
      for (final tag in incoming) {
        final idx = _tags.indexWhere((t) => t.epc == tag.epc);
        if (idx >= 0) {
          _tags[idx] = tag;
        } else {
          _tags.add(tag);
        }
      }
    });
  }

  Future<void> _startInventory() async {
    setState(() {
      _scanning = true;
      _tags.clear();
    });
    _addLog('Start inventory (button)', color: Colors.blue);
    await _service.startRfidInventory();
  }

  Future<void> _stopInventory() async {
    _clearTimer?.cancel();
    setState(() => _scanning = false);
    _addLog('Stop inventory (button)', color: Colors.blue);
    await _service.stopRfidInventory();
  }

  Future<void> _onPowerChanged(int index) async {
    setState(() => _powerIndex = index);
    await _service.setPower(index);
  }

  @override
  void dispose() {
    _clearTimer?.cancel();
    _sub?.cancel();
    _logScrollController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('RFID Inventory'),
        actions: [
          Center(
            child: Padding(
              padding: const EdgeInsets.only(right: 12),
              child: Text('${_tags.length} tags', style: const TextStyle(fontSize: 14)),
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
            color: _statusColor,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            child: Text(_status, style: const TextStyle(fontSize: 12), overflow: TextOverflow.ellipsis),
          ),

          // ── Power slider ───────────────────────────────────────────────
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
            child: Row(
              children: [
                const Text('Power:', style: TextStyle(fontSize: 13)),
                Expanded(
                  child: Slider(
                    value: _powerIndex.toDouble(),
                    min: 0,
                    max: _maxPowerIndex.toDouble(),
                    divisions: _maxPowerIndex > 0 ? _maxPowerIndex : 1,
                    onChanged: _scanning ? null : (v) => _onPowerChanged(v.round()),
                  ),
                ),
                SizedBox(
                  width: 36,
                  child: Text('$_powerIndex', style: const TextStyle(fontSize: 13), textAlign: TextAlign.right),
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
                  child: ElevatedButton(
                    onPressed: _scanning ? null : _startInventory,
                    child: const Text('Start'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: ElevatedButton(
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Colors.red.shade400,
                      foregroundColor: Colors.white,
                    ),
                    onPressed: _scanning ? _stopInventory : null,
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
              _scanning
                  ? '🔴 Scanning… (trigger release = stop)'
                  : 'Start button or physical trigger starts inventory',
              style: TextStyle(fontSize: 11, color: _scanning ? Colors.red : Colors.grey),
            ),
          ),

          const Divider(height: 1),

          // ── Tag list (flexible) ────────────────────────────────────────
          Expanded(
            flex: 3,
            child: _tags.isEmpty
                ? Center(
                    child: Text(
                      _scanning ? 'Scanning for tags…' : 'No tags read yet',
                      style: const TextStyle(color: Colors.grey),
                    ),
                  )
                : ListView.builder(
                    itemCount: _tags.length,
                    itemBuilder: (_, i) {
                      final tag = _tags[i];
                      return ListTile(
                        dense: true,
                        leading: const Icon(Icons.nfc, size: 18),
                        title: Text(
                          tag.epc,
                          style: const TextStyle(fontFamily: 'monospace', fontSize: 13),
                        ),
                        trailing: Text('${tag.rssi} dBm', style: const TextStyle(fontSize: 12)),
                      );
                    },
                  ),
          ),

          // ── Event log (expandable) ─────────────────────────────────────
          const Divider(height: 1),
          InkWell(
            onTap: () => setState(() => _logExpanded = !_logExpanded),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Row(
                children: [
                  Icon(_logExpanded ? Icons.expand_more : Icons.expand_less, size: 16),
                  const SizedBox(width: 6),
                  Text('Event Log (${_log.length})',
                      style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
                  const Spacer(),
                  if (_log.isNotEmpty)
                    GestureDetector(
                      onTap: () => setState(() => _log.clear()),
                      child: const Text('clear', style: TextStyle(fontSize: 11, color: Colors.grey)),
                    ),
                ],
              ),
            ),
          ),
          if (_logExpanded)
            Expanded(
              flex: 2,
              child: _log.isEmpty
                  ? const Center(child: Text('No events yet', style: TextStyle(color: Colors.grey, fontSize: 12)))
                  : ListView.builder(
                      controller: _logScrollController,
                      itemCount: _log.length,
                      itemBuilder: (_, i) {
                        final entry = _log[i];
                        return Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 1),
                          child: Text(
                            entry.message,
                            style: TextStyle(
                              fontSize: 11,
                              fontFamily: 'monospace',
                              color: entry.color,
                            ),
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

class _LogEntry {
  final String message;
  final Color color;
  _LogEntry(this.message, this.color);
}
