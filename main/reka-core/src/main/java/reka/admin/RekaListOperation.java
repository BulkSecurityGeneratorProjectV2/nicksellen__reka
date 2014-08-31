package reka.admin;

import reka.Application;
import reka.ApplicationManager;
import reka.api.Path;
import reka.api.data.MutableData;
import reka.api.run.SyncOperation;
import reka.core.data.memory.MutableMemoryData;



public class RekaListOperation implements SyncOperation {
	
	private final ApplicationManager manager;
	private final Path out;
	
	public RekaListOperation(ApplicationManager manager, Path out) {
		this.manager = manager;
		this.out = out;
	}

	@Override
	public MutableData call(MutableData data) {
		data.putList(out, list -> {
			manager.forEach(e -> {
				String identity = e.getKey();
				Application app = e.getValue();	
				MutableData item = MutableMemoryData.create();
				item.putString("id", identity);
				AdminUtils.putAppDetails(item.createMapAt("app"), app);
				list.add(item);
			});
		});
		return data;
	}

}
