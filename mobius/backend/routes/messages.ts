import express from 'express';
import { auth } from '../middleware/auth';
import { Messages } from '../repositories/messages';
import { hasEncryptedPlaceholder, decryptText } from '../services/secret-guard';

const router = express.Router();

router.patch('/:id/bookmark', auth, (req: express.Request, res: express.Response) => {
  const user = (req as any).user;
  const id = Number(req.params.id);
  if (!Number.isFinite(id)) {
    res.status(400).json({ error: 'Bad id' });
    return;
  }
  const msg = Messages.findWithUser(id);
  if (!msg || msg.user_id !== user.id) {
    res.status(404).json({ error: 'Not found' });
    return;
  }
  const newVal = msg.bookmarked ? 0 : 1;
  Messages.setBookmark(id, newVal);
  res.json({ id: msg.id, bookmarked: newVal });
});

// 消息内加密片段"按需解密": 仅消息本人可查看自己发送消息中被 secret-guard
// 加密的原文 (DB 常态只存密文占位符, 点开 🔒 时才经此接口还原)。
router.post('/:id/reveal', auth, (req: express.Request, res: express.Response) => {
  const user = (req as any).user;
  const id = Number(req.params.id);
  if (!Number.isFinite(id)) {
    res.status(400).json({ error: 'Bad id' });
    return;
  }
  const msg = Messages.findWithUser(id);
  if (!msg || msg.user_id !== user.id) {
    res.status(404).json({ error: 'Not found' });
    return;
  }
  if (!hasEncryptedPlaceholder(msg.content)) {
    res.status(400).json({ error: '该消息没有加密片段' });
    return;
  }
  res.json({ id: msg.id, content: decryptText(msg.content) });
});

export = router;
