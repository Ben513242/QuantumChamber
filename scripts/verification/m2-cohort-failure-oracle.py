"""唯讀檢查具名 M2 cohort 的原 GameTest 結果與獨立失敗收尾／等待證據。"""
import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MODES = ('partial-exception', 'timeout-false', 'slow-old10', 'slow-new60',
         'request-fault', 'witness-fault', 'late-crosswall')
TEST = 'm2corridorgametests.native_cohort_walks_and_returns_south_windows'
SENTINEL = 'm2corridorgametests.native_second_move_failure_rolls_back_hidden_nbt_whole_cohort_windows'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def fields(line, marker):
    """只解析固定欄位；suppressed 與 mapping/lease 尾段保持 opaque。"""
    segment = line.split(marker + ' ', 1)[1]
    tail = {}
    errors = []
    if marker == 'CI_W1_COHORT_WAIT FAIL':
        prefix = segment.partition(' failure=')[0].split(' mappingSlot=', 1)[0].split(' leaseSlot=', 1)[0]
    elif marker in ('CI_W1_COHORT_WAIT BEGIN', 'CI_W1_COHORT_WAIT READY'):
        prefix = segment.split(' mappingSlot=', 1)[0].split(' leaseSlot=', 1)[0]
    elif marker in ('CI_W1_INJECT_PARTIAL', 'CI_W1_TIMEOUT_HOLD'):
        prefix = segment.partition(' own=')[0]
    elif marker == 'CI_W1_ADAPTER_WITNESS_FAILURE':
        prefix, separator, remainder = segment.partition(' originalMessage=')
        match = re.match(r'(\S+)\s+sid=(\S+)\s+stage=(\S+)\s+cause=(\S+)\s+suppressed=(\S+)\s+firstSuppressed=(\S+)\s+lastSuppressed=(\S+)', remainder)
        deadline = re.search(r'\s+tick=(\d+)\s+deadlineTick=(\d+)$', remainder)
        if not separator or not match or not deadline:
            errors.append('WITNESS_FORMAT')
        else:
            tail = dict(zip(('originalMessage', 'originalSid', 'originalStage', 'cause',
                             'suppressed', 'firstSuppressed', 'lastSuppressed'), match.groups()))
            tail['tick'], tail['deadlineTick'] = deadline.groups()
    elif marker in ('CI_W1_ADAPTER_PREFAIL', 'CI_W1_ADAPTER_LATE'):
        prefix, separator, remainder = segment.partition(' firstSuppressed=')
        if separator:
            if marker == 'CI_W1_ADAPTER_LATE':
                match = re.search(r'\s+requestCount=(\d+)\s+requestAfterDeadline=(\d+)\s+scheduleAfterDeadline=(\d+)\s+delayed=(true|false)\s+tick=(\d+)\s+deadlineTick=(\d+)$', remainder)
                if match:
                    tail = dict(zip(('requestCount', 'requestAfterDeadline', 'scheduleAfterDeadline',
                                     'delayed', 'tick', 'deadlineTick'), match.groups()))
                    remainder = remainder[:match.start()]
                else:
                    errors.append('TAIL_FORMAT')
            first_suppressed = remainder.split()[0] if remainder.split() else ''
            tail['firstSuppressed'] = first_suppressed
        elif marker == 'CI_W1_ADAPTER_LATE':
            errors.append('MISSING_FIRST_SUPPRESSED')
    elif marker in ('CI_W1_E1_PAUSE_START', 'CI_W1_E1_PAUSE_END'):
        prefix = segment.partition(' own=')[0]
    else:
        prefix = segment
    values = {}
    for token in prefix.split():
        if '=' not in token:
            continue
        key, value = token.split('=', 1)
        if key in values:
            errors.append('DUPLICATE_' + key)
        values[key] = value
    for key, value in tail.items():
        if key in values:
            errors.append('DUPLICATE_' + key)
        values[key] = value
    return values, errors


def lease_metrics(line):
    return [tuple(map(int, item)) for item in re.findall(
        r'slot=(\d+) chunks=(\d+) FULL=(\d+) loaded=(\d+) ticking=(\d+)', line)]


def expected_resource(values, sid, phase='CLEAN', first_suppressed=None):
    wanted = {'sid': sid, 'phase': phase, 'presence': 'NONE', 'flushed': 'false',
              'current': 'false', 'sourceBbox': 'true', 'playersClosed': 'true',
              'operatorClosed': 'true', 'mayMutate': 'true', 'spaceAbsent': 'true',
              'receiptOutstanding': 'false'}
    if first_suppressed is not None:
        wanted.update({'suppressed': '1', 'firstSuppressed': first_suppressed})
    return {key: {'expected': want, 'actual': values.get(key)}
            for key, want in wanted.items() if values.get(key) != want}


def expected_post(values, sid, closed=True):
    wanted = {'sid': sid, 'presence': 'NONE', 'flushed': 'false', 'current': 'false',
              'playersClosed': str(closed).lower(), 'operatorClosed': str(closed).lower(),
              'spaceAbsent': 'true', 'receiptOutstanding': 'false'}
    return {key: {'expected': want, 'actual': values.get(key)}
            for key, want in wanted.items() if values.get(key) != want}


def status(conditions):
    return 'PASS' if all(conditions.values()) else 'FAIL'


def main():
    if sys.version_info < (3, 8):
        raise SystemExit('需要 Python 3.8+ 標準函式庫；不下載依賴')
    parser = argparse.ArgumentParser()
    parser.add_argument('--scenario', required=True, choices=MODES)
    parser.add_argument('--gate', required=True)
    parser.add_argument('--fixture', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    gate, fixture, output = Path(args.gate), Path(args.fixture), Path(args.output)
    if output.exists():
        raise SystemExit('拒絕覆寫 oracle 輸出：' + str(output))
    paths = {'xml': gate / 'result-build/gametest-results.xml',
             'log': gate / 'execution/runtime-logs/latest.log',
             'manifest': gate / 'execution/runtime-log.json',
             'execution': gate / 'execution/execution.json',
             'worldRestore': gate / 'world-restore.json',
             'sourceRestore': fixture / 'restore.json'}
    report = {'schema': 1, 'scenario': args.scenario, 'gateRoot': str(gate),
              'fixtureRoot': str(fixture), 'oracleVerdict': 'FAIL',
              'sourceFiles': {key: {'path': str(path), 'exists': path.is_file(),
                                    'sha256': sha(path) if path.is_file() else None}
                              for key, path in paths.items()}}
    if all(path.is_file() for path in paths.values()):
        root = ET.parse(paths['xml']).getroot()
        cases = list(root.iter('testcase'))
        failures = [(case, child) for case in cases for child in case if child.tag == 'failure']
        target = [case for case in cases if case.attrib.get('classname') == 'quantumchamber:m1_empty'
                  and case.attrib.get('name') == TEST]
        target_failure = [child for case in target for child in case if child.tag == 'failure']
        sentinel = [case for case in cases if case.attrib.get('classname') == 'quantumchamber:m1_empty'
                    and case.attrib.get('name') == SENTINEL]
        lines = paths['log'].read_text(encoding='utf-8').splitlines()
        def tagged(marker):
            return [(number, line) for number, line in enumerate(lines, 1) if marker in line]
        marker = ('CI_W1_E1_PAUSE_START' if args.scenario.startswith('slow-') else
                  'CI_W1_TIMEOUT_HOLD' if args.scenario == 'timeout-false' else
                  'CI_W1_INJECT_PARTIAL')
        inject = tagged(marker + ' sid=')
        initial, initial_errors = fields(inject[0][1], marker) if len(inject) == 1 else ({}, [])
        sid = initial.get('sid')
        pre = tagged('CI_W1_ADAPTER_PREFAIL sid=')
        post = tagged('CI_W1_ADAPTER_POSTFAIL sid=')
        pre_values, pre_errors = fields(pre[0][1], 'CI_W1_ADAPTER_PREFAIL') if len(pre) == 1 else ({}, [])
        post_values, post_errors = fields(post[0][1], 'CI_W1_ADAPTER_POSTFAIL') if len(post) == 1 else ({}, [])
        manifest = json.loads(paths['manifest'].read_text(encoding='utf-8-sig'))
        execution = json.loads(paths['execution'].read_text(encoding='utf-8-sig'))
        world_restore = json.loads(paths['worldRestore'].read_text(encoding='utf-8-sig'))
        source_restore = json.loads(paths['sourceRestore'].read_text(encoding='utf-8-sig'))
        selected = [piece for piece in manifest.get('pieces', [])
                    if piece.get('path') == 'runtime-logs/latest.log' and piece.get('selected')]
        manifest_log_ok = len(selected) == 1 and selected[0].get('sha256', '').lower() == sha(paths['log']).lower()
        before_blob = source_restore.get('afterBlob')
        before_sha = source_restore.get('afterSha256')
        setup = {}
        if (fixture / 'setup.json').is_file():
            setup = json.loads((fixture / 'setup.json').read_text(encoding='utf-8-sig'))
            before_blob = setup.get('sourceBlob', setup.get('beforeBlob', before_blob))
            before_sha = setup.get('sourceSha256', setup.get('beforeSha256', before_sha))
        script_files = {'fixture': 'm2-cohort-failure-fixture.ps1',
                        'adapter': 'm2-cohort-failure-adapter.ps1',
                        'oracle': 'm2-cohort-failure-oracle.py', 'readme': 'README.md'}
        pins = setup.get('scriptPins')
        pin_checks = {key: ((fixture / 'verification-sources' / filename).is_file() and
                            pins.get(key, {}).get('path') == filename and
                            str(pins.get(key, {}).get('sha256', '')).lower() ==
                            sha(fixture / 'verification-sources' / filename).lower())
                      for key, filename in script_files.items()} if isinstance(pins, dict) else {}
        restore_ok = (source_restore.get('sourceRestored') is True and
                      str(source_restore.get('afterBlob', '')).lower() == str(before_blob).lower() and
                      str(source_restore.get('afterSha256', '')).lower() == str(before_sha).lower() and
                      world_restore.get('exactRestored') is True)
        expected_pass = args.scenario == 'slow-new60'
        original_gate_ok = execution.get('exitCode') == (0 if expected_pass else 1)
        xml_ok = (len(cases) == 179 and len(target) == len(sentinel) == 1 and
                  not any(child.tag == 'failure' for child in sentinel) and
                  (len(failures) == len(target_failure) == 0 if expected_pass else
                   len(failures) == len(target_failure) == 1))
        if not expected_pass and len(target_failure) == 1 and sid:
            failure_message = target_failure[0].attrib.get('message', '')
            original_failure_ok = (sid in failure_message and
                                   ('有限等待逾時' if args.scenario in ('timeout-false', 'slow-old10')
                                    else 'CI_W1_PARTIAL_WAIT_FAIL') in failure_message)
        else:
            failure_message = None
            original_failure_ok = expected_pass
        next_batch = next(((number, line) for number, line in tagged('Running test batch')
                           if inject and number > inject[0][0]), None)
        resource_conditions = {}
        if not expected_pass:
            phase = ('CLEAN_WITH_RETRY_ERROR' if args.scenario == 'request-fault' else
                     'LATE_BACKEND_CLEAN' if args.scenario == 'late-crosswall' else 'CLEAN')
            wanted_suppressed = 'CI_W1_CLEANUP_FAULT' if args.scenario == 'request-fault' else None
            pre_mismatch = expected_resource(pre_values, sid, phase, wanted_suppressed)
            post_mismatch = expected_post(post_values, sid, closed=args.scenario != 'witness-fault')
            resource_conditions = {
                'witnessCount': len(pre) == len(post) == 1,
                'witnessParse': not pre_errors and not post_errors,
                'witnessFields': not pre_mismatch and not post_mismatch,
                'order': (bool(next_batch) and len(inject) == len(pre) == len(post) == 1 and
                          inject[0][0] < pre[0][0] < next_batch[0] < post[0][0]),
                'noWitnessError': not tagged('CI_W1_ADAPTER_PREFAIL_ERROR') and not tagged('CI_W1_ADAPTER_POSTFAIL_ERROR'),
            }
        else:
            pre_mismatch, post_mismatch = {}, {}
            resource_conditions = {'noFailureWitness': not pre and not post}
        scenario_conditions = {}
        if args.scenario in ('partial-exception', 'request-fault', 'witness-fault', 'late-crosswall'):
            metrics = lease_metrics(inject[0][1]) if len(inject) == 1 else []
            begun = tagged('CI_W1_COHORT_WAIT BEGIN sid=' + str(sid) + ' stage=3 ') if sid else []
            begun = [entry for entry in begun if len(inject) == 1 and entry[0] < inject[0][0]]
            begin_values, begin_errors = fields(begun[0][1], 'CI_W1_COHORT_WAIT BEGIN') if len(begun) == 1 else ({}, [])
            mapping_slots = re.findall(r'\bmappingSlot=(\d+)', begun[0][1]) if len(begun) == 1 else []
            old_slots = set(mapping_slots)
            new_metrics = [metric for metric in metrics if str(metric[0]) not in old_slots]
            scenario_conditions = {'stage3Partial': initial.get('stage') == '3' and initial.get('expected') == '5'
                and initial.get('current') == '4' and initial.get('instances') == '1' and initial.get('leases') == '3',
                'mappingBaseline': len(begun) == 1 and not begin_errors and begin_values.get('sid') == sid
                    and begin_values.get('stage') == '3' and begin_values.get('instances') == '1'
                    and len(mapping_slots) == len(old_slots) == 1,
                'realNewLeaseNotReady': len(metrics) == 3 and len({metric[0] for metric in metrics}) == 3
                    and len(new_metrics) == 2 and any(full < total or loaded < total or ticking < total
                    for _, total, full, loaded, ticking in new_metrics)}
        if args.scenario in ('slow-old10', 'slow-new60'):
            pause_end = tagged('CI_W1_E1_PAUSE_END sid=')
            ended, ended_errors = fields(pause_end[0][1], 'CI_W1_E1_PAUSE_END') if len(pause_end) == 1 else ({}, [])
            before, after = (lease_metrics(inject[0][1]) if len(inject) == 1 else [],
                             lease_metrics(pause_end[0][1]) if len(pause_end) == 1 else [])
            scenario_conditions.update({'pauseOnce': len(inject) == len(pause_end) == 1,
                'pauseParse': not initial_errors and not ended_errors,
                'truePending': initial.get('stage') == '3' and initial.get('expected') == '5'
                    and initial.get('current') == '4' and initial.get('instances') == '1'
                    and initial.get('leases') == '3' and initial.get('newUnderReady') == 'true'
                    and initial.get('predicate') == 'false' and len(before) == len(after) == 3
                    and any(full < total or loaded < total or ticking < total
                            for _, total, full, loaded, ticking in before),
                'noEarlyPublish': ended.get('current') == '4' and ended.get('predicate') == 'false',
                'pause25Seconds': int(ended.get('elapsedMs', '-1')) >= 25000
                    and int(ended.get('endNanos', '-1')) - int(ended.get('startNanos', '-1')) >= 25_000_000_000,
                'sameServerTick': ended.get('startServerTick') == ended.get('endServerTick'),
                'deadlineSide': ended.get('deadlineExpired') == ('false' if expected_pass else 'true'),
                'pauseBeforeResult': len(inject) == len(pause_end) == 1 and inject[0][0] < pause_end[0][0],
            })
            if expected_pass:
                ready = tagged('CI_W1_COHORT_WAIT READY sid=')
                ready_rows = []
                for number, line in ready:
                    row, errors = fields(line, 'CI_W1_COHORT_WAIT READY')
                    if errors:
                        break
                    ready_rows.append((row.get('sid'), row.get('stage'), row.get('currentEpoch'),
                                       row.get('expectedEpoch'), row.get('elapsedMs')))
                south = [row for row in ready_rows if row[0] == sid]
                stage3 = [row for row in south if row[1] == '3']
                scenario_conditions.update({'ready28': len(ready_rows) == 28 and len(set(row[:2] for row in ready_rows)) == 28
                    and not tagged('CI_W1_COHORT_WAIT FAIL sid='),
                    'southStage3Ready': len(south) == 7 and len(stage3) == 1 and stage3[0][2] == stage3[0][3] == '5'
                    and 25000 <= int(stage3[0][4]) < 60000})
        if args.scenario == 'timeout-false':
            wait_fail = tagged('CI_W1_COHORT_WAIT FAIL sid=')
            cleanup_begin = tagged('CI_W1_COHORT_CLEANUP BEGIN sid=')
            waited, wait_errors = fields(wait_fail[0][1], 'CI_W1_COHORT_WAIT FAIL') if len(wait_fail) == 1 else ({}, [])
            cleanup, cleanup_errors = fields(cleanup_begin[0][1], 'CI_W1_COHORT_CLEANUP BEGIN') if len(cleanup_begin) == 1 else ({}, [])
            scenario_conditions.update({'finiteFailureCount': len(wait_fail) == len(cleanup_begin) == 1,
                'finiteFailureParse': not wait_errors and not cleanup_errors,
                'finiteFailureSidStage': waited.get('sid') == cleanup.get('sid') == sid and waited.get('stage') == '3',
                'wallOrTickLimit': (int(waited.get('elapsedMs', '-1')) >= 60000 and
                                    int(waited.get('remainingMs', '999999')) <= 0) or
                                   int(cleanup.get('startTick', '-1')) >= 90000})
        if args.scenario == 'request-fault':
            scenario_conditions['requestFaultPreserved'] = (pre_values.get('suppressed') == '1' and
                                                              pre_values.get('firstSuppressed') == 'CI_W1_CLEANUP_FAULT')
        if args.scenario == 'witness-fault':
            witness = tagged('CI_W1_ADAPTER_WITNESS_FAILURE sid=')
            final = tagged('CI_W1_COHORT_CLEANUP FINAL sid=')
            direct, direct_errors = fields(witness[0][1], 'CI_W1_ADAPTER_WITNESS_FAILURE') if len(witness) == 1 else ({}, [])
            scenario_conditions.update({'boundedWitnessCount': len(witness) == len(final) == 1,
                'boundedWitnessParse': not direct_errors,
                'originalThrowable': direct.get('sid') == direct.get('originalSid') == sid
                    and direct.get('originalStage') == '3'
                    and direct.get('originalType') == 'java.lang.IllegalStateException'
                    and direct.get('originalMessage') == 'CI_W1_PARTIAL_WAIT_FAIL' and direct.get('cause') == 'null',
                'suppressedFault': int(direct.get('suppressed', '0')) >= 2 and
                    direct.get('firstSuppressed') == 'CI_W1_WITNESS_FAULT' and
                    direct.get('lastSuppressed', '').startswith('具名cohort收尾未完成'),
                'absoluteDeadline': direct.get('tick') == direct.get('deadlineTick') and direct.get('tick') is not None,
                'incomplete': len(final) == 1 and 'phase=INCOMPLETE' in final[0][1],
                'postConnectionsRetained': post_values.get('playersClosed') == 'false'
                    and post_values.get('operatorClosed') == 'false'})
            # 此模式沒有CLEAN時點；原FAIL前的直接Throwable receipt取代PREFAIL資源成功契約。
            resource_conditions = {'directWitnessCount': len(witness) == len(post) == 1,
                'postWitnessParse': not post_errors,
                'postOrder': bool(next_batch) and len(witness) == len(post) == 1 and
                             witness[0][0] < next_batch[0] < post[0][0],
                'noPostWitnessError': not tagged('CI_W1_ADAPTER_POSTFAIL_ERROR')}
        if args.scenario == 'late-crosswall':
            began = tagged('CI_W1_LATE_SLEEP_BEGIN sid=')
            ended = tagged('CI_W1_LATE_SLEEP_END sid=')
            late = tagged('CI_W1_ADAPTER_LATE sid=')
            final = tagged('CI_W1_COHORT_CLEANUP FINAL sid=')
            begin_values, begin_errors = fields(began[0][1], 'CI_W1_LATE_SLEEP_BEGIN') if len(began) == 1 else ({}, [])
            end_values, end_errors = fields(ended[0][1], 'CI_W1_LATE_SLEEP_END') if len(ended) == 1 else ({}, [])
            late_values, late_errors = fields(late[0][1], 'CI_W1_ADAPTER_LATE') if len(late) == 1 else ({}, [])
            scenario_conditions.update({'lateReceiptCount': len(began) == len(ended) == len(late) == len(final) == 1,
                'lateReceiptParse': not begin_errors and not end_errors and not late_errors,
                'sameSid': begin_values.get('sid') == end_values.get('sid') == late_values.get('sid') == sid,
                'crossedWall': int(begin_values.get('remainingMs', '0')) > 0 and
                               int(end_values.get('overdueMs', '0')) >= 100,
                'noLateRequestOrSchedule': late_values.get('requestAfterDeadline') ==
                    end_values.get('requestAfterDeadline') == '0' and late_values.get('scheduleAfterDeadline') == '0',
                'lateOriginal': late_values.get('phase') == pre_values.get('phase') == 'LATE_BACKEND_CLEAN'
                    and int(late_values.get('suppressed', '0')) >= 1
                    and late_values.get('firstSuppressed', '').startswith('測試收尾在期限後才確認正式返還')
                    and len(final) == 1 and 'phase=LATE_BACKEND_CLEAN' in final[0][1],
                'lateOrder': len(began) == len(ended) == len(late) == len(pre) == 1 and
                    began[0][0] < ended[0][0] < late[0][0] < pre[0][0]})
        conditions = {'evidenceComplete': manifest_log_ok and restore_ok,
                      'originalGate': original_gate_ok,
                      'originalXml': xml_ok and original_failure_ok,
                      'targetMarker': len(inject) == 1 and sid is not None and not initial_errors,
                      'sentinel': len(sentinel) == 1 and not any(child.tag == 'failure' for child in sentinel)}
        if pins is not None:
            conditions['verificationSourcePins'] = len(pin_checks) == 4 and all(pin_checks.values())
        conditions.update({'resource.' + key: value for key, value in resource_conditions.items()})
        conditions.update({'scenario.' + key: value for key, value in scenario_conditions.items()})
        report.update({'originalGateExit': execution.get('exitCode'), 'originalXmlCases': len(cases),
                       'originalXmlFailures': len(failures), 'targetFailureMessage': failure_message,
                       'sid': sid, 'conditions': conditions, 'failedConditions': [key for key, value in conditions.items() if not value],
                       'preMismatches': pre_mismatch, 'postMismatches': post_mismatch,
                       'scriptPinsFromSetup': pins, 'scriptPinChecks': pin_checks,
                       'sourceRestore': source_restore, 'worldRestore': world_restore,
                       'markers': {'injection': inject, 'pre': pre, 'post': post}})
        report['oracleVerdict'] = status(conditions)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('ORIGINAL_GATE_EXIT=' + str(report.get('originalGateExit')))
    print('ORACLE_VERDICT=' + report['oracleVerdict'])
    print('ORACLE_OUTPUT=' + str(output))


if __name__ == '__main__':
    main()
