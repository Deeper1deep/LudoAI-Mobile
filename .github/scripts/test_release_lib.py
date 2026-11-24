import json, os, sys, tempfile, unittest, datetime
sys.path.insert(0, os.path.dirname(__file__))
import release_lib as r

APP = 'com.ludoai.assistant'; CERT = 'af' * 32

class T(unittest.TestCase):
    def apk(self, data=b'apk-bytes'):
        d = tempfile.mkdtemp(); p = os.path.join(d, 'a.apk'); open(p, 'wb').write(data); return p

    def meta(self, p, **kw):
        a = dict(app_id=APP, owner='o', repo='u', version_name='1.0.8', version_code=9, apk_path=p, cert=CERT, min_code=1, mandatory=False, notes_en='hi', notes_ar='')
        a.update(kw); return r.build_metadata(**a)

    def test_monotonic_version_takes_max_of_all_sources(self):
        mx = r.max_published_code({'versionCode': 5}, [{'versionCode': 7}, {'versionCode': 6}], ['v1.0.8', 'junk'])
        self.assertEqual(9, mx)
        self.assertEqual((10, '1.0.9'), r.next_version({'VERSION_MAJOR': '1', 'VERSION_MINOR': '0', 'VERSION_CODE': '1'}, mx))
        self.assertEqual((1, '1.0.0'), r.next_version({'VERSION_MAJOR': '1', 'VERSION_MINOR': '0', 'VERSION_CODE': '1'}, 0))
        self.assertEqual((20, '1.0.19'), r.next_version({'VERSION_MAJOR': '1', 'VERSION_MINOR': '0', 'VERSION_CODE': '20'}, 3))

    def test_metadata_matches_apk_and_validates(self):
        p = self.apk(); m = self.meta(p)
        self.assertEqual([], r.validate_metadata(m, p, CERT, APP, 8, 'o', 'u'))
        self.assertEqual(len(b'apk-bytes'), m['sizeBytes'])
        self.assertTrue(m['publishedAt'].endswith('Z'))

    def test_validation_catches_every_inconsistency(self):
        p = self.apk(); m = self.meta(p)
        self.assertIn('versionCode not increasing', r.validate_metadata(m, p, CERT, APP, 9, 'o', 'u'))
        self.assertIn('certSha256 != pinned', r.validate_metadata(m, p, 'bb' * 32, APP, 8, 'o', 'u'))
        self.assertIn('sha256 != apk', r.validate_metadata(m, self.apk(b'other'), CERT, APP, 8, 'o', 'u'))
        self.assertIn('applicationId', r.validate_metadata(m, p, CERT, 'x.y', 8, 'o', 'u'))
        self.assertIn('apkUrl', r.validate_metadata(m, p, CERT, APP, 8, 'o', 'other'))
        bad = dict(m, minVersionCode=99)
        self.assertIn('minVersionCode', r.validate_metadata(bad, p, CERT, APP, 8, 'o', 'u'))

    def test_notes_are_data_not_shell(self):
        evil = 'x"; rm -rf / #$(id)`id`\n\x00\x07line2'
        m = self.meta(self.apk(), notes_en=evil)
        j = json.loads(json.dumps(m, ensure_ascii=False))
        self.assertEqual('x"; rm -rf / #$(id)`id`\nline2', j['releaseNotes']['en'])      # preserved literally, control chars stripped
        self.assertEqual(4000, len(r.clean_notes('a' * 9000)))

    def test_history_is_append_only(self):
        a = {'versionCode': 5, 'sha256': '1'}; b = {'versionCode': 6, 'sha256': '2'}
        self.assertEqual([a, b], r.merge_history([a], b))
        self.assertEqual([a, b], r.merge_history([a, b], b))                                # idempotent re-run
        with self.assertRaises(ValueError): r.merge_history([a, b], {'versionCode': 6, 'sha256': 'x'})
        with self.assertRaises(ValueError): r.merge_history([a, b], {'versionCode': 4, 'sha256': 'y'})

    def test_badging(self):
        ok = "package: name='com.ludoai.assistant' versionCode='9' versionName='1.0.8' platformBuildVersionName='14'\nsdkVersion:'26'\n"
        i = r.parse_badging(ok)
        self.assertEqual((APP, 9, '1.0.8', False), (i['package'], i['versionCode'], i['versionName'], i['debuggable']))
        self.assertTrue(r.parse_badging(ok + "application-debuggable\n")['debuggable'])

if __name__ == '__main__':
    unittest.main()
